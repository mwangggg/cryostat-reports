package io.cryostat.reports;

import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Produces;

import io.cryostat.core.log.Logger;
import io.cryostat.core.reports.ReportGenerator;
import io.cryostat.core.sys.FileSystem;

public class Producers {

    @Produces
    @ApplicationScoped
    ReportGenerator produceReportGenerator() {
        return new ReportGenerator(Logger.INSTANCE, Set.of());
    }

    @Produces
    @ApplicationScoped
    FileSystem produceFileSystem() {
        return new FileSystem();
    }
}
