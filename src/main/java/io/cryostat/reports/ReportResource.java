/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.reports.InterruptibleReportGenerator.AnalysisResult;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.core.util.RuleFilterParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.openjdk.jmc.common.io.IOToolkit;
import org.openjdk.jmc.flightrecorder.rules.IRule;

@Path("/")
public class ReportResource {

    private static final String SINGLETHREAD_PROPERTY =
            "org.openjdk.jmc.flightrecorder.parser.singlethreaded";

    @ConfigProperty(name = "io.cryostat.reports.memory-factor", defaultValue = "10")
    String memoryFactor;

    @ConfigProperty(name = "io.cryostat.reports.timeout", defaultValue = "29000")
    String timeoutMs;

    @Inject Logger logger;
    @Inject InterruptibleReportGenerator generator;
    @Inject FileSystem fs;

    RuleFilterParser rfp = new RuleFilterParser();

    void onStart(@Observes StartupEvent ev) {
        logger.infof(
                "CPUs: %d singlethread: %b maxMemory: %dM memoryFactor: %s timeout: %sms",
                Runtime.getRuntime().availableProcessors(),
                Boolean.getBoolean(SINGLETHREAD_PROPERTY),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                memoryFactor,
                timeoutMs);
    }

    @Path("health")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public void healthCheck() {}

    @Blocking
    @Path("report")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @POST
    public String getEval(RoutingContext ctx, @BeanParam RecordingFormData form)
            throws IOException {
        FileUpload upload = form.file;

        Pair<java.nio.file.Path, Pair<Long, Long>> uploadResult = handleUpload(upload);
        java.nio.file.Path file = uploadResult.getLeft();
        long timeout = TimeUnit.MILLISECONDS.toNanos(Long.parseLong(timeoutMs));
        long start = uploadResult.getRight().getLeft();
        long elapsed = uploadResult.getRight().getRight();

        Predicate<IRule> predicate = rfp.parse(form.filter);
        Future<Map<String, AnalysisResult>> evalMapFuture = null;

        ObjectMapper oMapper = new ObjectMapper();
        try (var stream = fs.newInputStream(file)) {
            evalMapFuture = generator.generateEvalMapInterruptibly(stream, predicate);
            ctxHelper(ctx, evalMapFuture);
            return oMapper.writeValueAsString(
                    evalMapFuture.get(timeout - elapsed, TimeUnit.NANOSECONDS));
        } catch (ExecutionException | InterruptedException e) {
            throw new InternalServerErrorException(e);
        } catch (TimeoutException e) {
            throw new ServerErrorException(Response.Status.GATEWAY_TIMEOUT, e);
        } finally {
            cleanupHelper(evalMapFuture, file, upload.fileName(), start);
        }
    }

    private Pair<java.nio.file.Path, Pair<Long, Long>> handleUpload(FileUpload upload)
            throws IOException {
        java.nio.file.Path file = upload.uploadedFile();
        long timeout = TimeUnit.MILLISECONDS.toNanos(Long.parseLong(timeoutMs));
        long start = System.nanoTime();
        long now = start;
        long elapsed = 0;

        logger.infof("Received request for %s (%d bytes)", upload.fileName(), upload.size());

        if (IOToolkit.isCompressedFile(file.toFile())) {
            file = decompress(file);
            now = System.nanoTime();
            elapsed = now - start;
            logger.infof(
                    "%s was compressed. Decompressed size: %d bytes. Decompression took %dms",
                    upload.fileName(),
                    file.toFile().length(),
                    TimeUnit.NANOSECONDS.toMillis(elapsed));
        }

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long availableMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        long maxHandleableSize = availableMemory / Long.parseLong(memoryFactor);
        if (file.toFile().length() > maxHandleableSize) {
            throw new ClientErrorException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
        }

        now = System.nanoTime();
        elapsed = now - start;
        if (elapsed > timeout) {
            throw new ServerErrorException(Response.Status.GATEWAY_TIMEOUT);
        }
        return Pair.of(file, Pair.of(start, elapsed));
    }

    private void ctxHelper(RoutingContext ctx, Future<?> ff) {
        ctx.response()
                .exceptionHandler(
                        e -> {
                            logger.error(e);
                            ff.cancel(true);
                        });
        ctx.request()
                .exceptionHandler(
                        e -> {
                            logger.error(e);
                            ff.cancel(true);
                        });
        ctx.addEndHandler().onComplete(ar -> ff.cancel(true));
    }

    private void cleanupHelper(
            Future<?> future, java.nio.file.Path file, String fileName, long start)
            throws IOException {
        if (future != null) {
            future.cancel(true);
        }
        boolean deleted = fs.deleteIfExists(file);
        if (deleted) {
            logger.infof("Deleted %s", file);
        } else {
            logger.infof("Failed to delete %s", file);
        }
        logger.infof(
                "Completed request for %s after %dms",
                fileName, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private java.nio.file.Path decompress(java.nio.file.Path file) throws IOException {
        java.nio.file.Path tmp = Files.createTempFile(null, null);
        try (var stream = IOToolkit.openUncompressedStream(file.toFile())) {
            fs.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } finally {
            fs.deleteIfExists(file);
        }
    }
}
