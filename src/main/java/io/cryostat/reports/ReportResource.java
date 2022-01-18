package io.cryostat.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.sys.FileSystem;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.MultipartForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.openjdk.jmc.common.io.IOToolkit;

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
    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public String getReport(RoutingContext ctx, @MultipartForm RecordingFormData form)
            throws IOException {
        FileUpload upload = form.file;
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

        Future<String> future = null;
        try (var stream = fs.newInputStream(file)) {
            future = generator.generateReportInterruptibly(stream);
            var ff = future;
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
            return future.get(timeout - elapsed, TimeUnit.NANOSECONDS);
        } catch (ExecutionException | InterruptedException e) {
            throw new InternalServerErrorException(e);
        } catch (TimeoutException e) {
            throw new ServerErrorException(Response.Status.GATEWAY_TIMEOUT, e);
        } finally {
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
                    upload.fileName(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
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
