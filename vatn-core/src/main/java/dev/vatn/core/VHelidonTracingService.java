package dev.vatn.core;

import dev.vatn.api.VTracingService;
import io.helidon.tracing.Tracer;
import io.helidon.tracing.TracerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * VTracingService backed by Helidon's OpenTelemetry tracing integration.
 *
 * <p>Activated automatically by VNodeRunner when {@code VATN_OTLP_ENDPOINT} is set.
 * The OTLP exporter is configured via standard OpenTelemetry env vars:
 * <ul>
 *   <li>{@code VATN_OTLP_ENDPOINT} (e.g. {@code http://localhost:4317}) — activates OTLP export</li>
 *   <li>{@code OTEL_SERVICE_NAME} — service name shown in traces (default: {@code vatn-node})</li>
 *   <li>{@code OTEL_RESOURCE_ATTRIBUTES} — additional resource attributes</li>
 * </ul>
 *
 * <p>When {@code VATN_OTLP_ENDPOINT} is absent, VNodeRunner registers {@link VTracingService#noop()}
 * and this class is never instantiated.
 */
public class VHelidonTracingService implements VTracingService {
    private static final Logger logger = LoggerFactory.getLogger(VHelidonTracingService.class);

    private final Tracer tracer;
    private final boolean enabled;

    public VHelidonTracingService(String otlpEndpoint, String serviceName) {
        Tracer t = null;
        boolean ok = false;
        try {
            // Set OTLP endpoint for Helidon/OTel auto-configuration
            System.setProperty("otel.exporter.otlp.endpoint", otlpEndpoint);
            System.setProperty("otel.service.name",
                    serviceName != null ? serviceName : "vatn-node");
            System.setProperty("otel.traces.exporter", "otlp");

            t = TracerBuilder.create(serviceName != null ? serviceName : "vatn-node")
                    .collectorUri(java.net.URI.create(otlpEndpoint))
                    .registerGlobal(true)
                    .build();
            ok = true;
            logger.info("[VATN-TRACING] OTLP tracing active → {}", otlpEndpoint);
        } catch (Exception e) {
            logger.warn("[VATN-TRACING] Failed to initialize OTLP tracer, falling back to noop: {}", e.getMessage());
        }
        this.tracer = t;
        this.enabled = ok;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public Span start(String operationName, Map<String, String> tags) {
        if (!enabled || tracer == null) return Span.noop();
        try {
            io.helidon.tracing.Span helidonSpan = tracer.spanBuilder(operationName)
                    .start();
            tags.forEach(helidonSpan::tag);
            return new HelidonSpanAdapter(helidonSpan);
        } catch (Exception e) {
            logger.debug("[VATN-TRACING] Failed to start span '{}': {}", operationName, e.getMessage());
            return Span.noop();
        }
    }

    private static final class HelidonSpanAdapter implements Span {
        private final io.helidon.tracing.Span delegate;

        HelidonSpanAdapter(io.helidon.tracing.Span delegate) { this.delegate = delegate; }

        @Override public void tag(String key, String value)  { delegate.tag(key, value); }
        @Override public void log(String event)              { delegate.addEvent(event); }
        @Override public void error(String message) {
            delegate.status(io.helidon.tracing.Span.Status.ERROR);
            delegate.addEvent("error: " + message);
            delegate.end();
        }
        @Override public void finish() { delegate.end(); }
    }
}
