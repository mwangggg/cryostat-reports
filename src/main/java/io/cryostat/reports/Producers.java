package io.cryostat.reports;

import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Produces;

import io.cryostat.core.log.Logger;
import io.cryostat.core.reports.ReportGenerator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.extension.jfr.JfrSpanProcessor;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

public class Producers {

    @Produces
    @ApplicationScoped
    ReportGenerator produceReportGenerator() {
        return new ReportGenerator(Logger.INSTANCE, Set.of());
    }

    @Produces
    @ApplicationScoped
    OpenTelemetry produceOpenTelemetry() {
        OtlpGrpcSpanExporter otlpExporter =
                OtlpGrpcSpanExporter.builder().setEndpoint("http://localhost:4317").build();

        SdkTracerProvider sdkTracerProvider =
                SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(new JfrSpanProcessor())
                        .addSpanProcessor(SimpleSpanProcessor.create(otlpExporter))
                        .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

}
