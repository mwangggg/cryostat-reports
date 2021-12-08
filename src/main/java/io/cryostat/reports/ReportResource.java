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
import io.smallrye.common.annotation.Blocking;

@Path("/")
public class ReportResource {

    private final ReportGenerator generator;
    private final FileSystem fs;

    @Inject
    ReportResource(ReportGenerator generator, FileSystem fs) {
        this.generator = generator;
        this.fs = fs;
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
        try (var stream = fs.newInputStream(form.file.uploadedFile())) {
            return generator.generateReport(stream);
        } finally {
            fs.deleteIfExists(form.file.uploadedFile());
        }
    }
}
