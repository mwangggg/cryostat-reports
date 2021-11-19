package io.cryostat.reports;

import java.io.IOException;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.MultipartForm;

import io.cryostat.core.reports.ReportGenerator;
import io.cryostat.core.sys.FileSystem;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import io.smallrye.common.annotation.Blocking;

@Path("/")
public class ReportResource {

    private final ReportGenerator generator;
    private final FileSystem fs;
    private final OpenTelemetry otel;
    private final Tracer tracer;

    @Inject
    ReportResource(ReportGenerator generator, FileSystem fs, OpenTelemetry otel) {
        this.generator = generator;
        this.fs = fs;
        this.otel = otel;
        this.tracer = otel.getTracer(getClass().getCanonicalName());
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
    public String addRecording(@javax.ws.rs.core.Context HttpHeaders headers, @MultipartForm RecordingFormData form) throws IOException {
        Context context = otel.getPropagators().getTextMapPropagator().extract(Context.current(), headers, new TextMapGetter<>() {

            @Override
            public Iterable<String> keys(HttpHeaders carrier) {
                return carrier.getRequestHeaders().keySet();
            }

            @Override
            public String get(HttpHeaders carrier, String key) {
                return carrier.getHeaderString(key);
            }
        });

        try (Scope scope = context.makeCurrent()) {
            Span span = tracer.spanBuilder("POST /report")
                .setSpanKind(SpanKind.SERVER)
                .startSpan()
                .setAttribute(SemanticAttributes.HTTP_METHOD, "POST")
                .setAttribute(SemanticAttributes.HTTP_SCHEME, "http")
                .setAttribute(SemanticAttributes.HTTP_TARGET, "/report");

            try (var stream = fs.newInputStream(form.file.uploadedFile())) {
                return generator.generateReport(stream);
            } finally {
                fs.deleteIfExists(form.file.uploadedFile());
                span.end();
            }
        }
    }
}
