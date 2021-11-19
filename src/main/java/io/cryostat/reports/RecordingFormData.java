package io.cryostat.reports;

import javax.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

public class RecordingFormData {
    @RestForm
    @PartType(MediaType.APPLICATION_OCTET_STREAM)
    public FileUpload file;
}
