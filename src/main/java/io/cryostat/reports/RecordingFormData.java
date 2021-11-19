package io.cryostat.reports;

import java.io.InputStream;

import javax.ws.rs.core.MediaType;

import org.jboss.resteasy.annotations.jaxrs.FormParam;
import org.jboss.resteasy.annotations.providers.multipart.PartType;

public class RecordingFormData {
    @FormParam
    @PartType(MediaType.APPLICATION_OCTET_STREAM)
    public InputStream file;
}
