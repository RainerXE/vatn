package dev.vatn.plugins.metrics;

import dev.vatn.api.VService;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * VATN service wrapper for the Micrometer {@link MeterRegistry}.
 * Registered by {@link MetricsPlugin} so other plugins can record custom metrics.
 *
 * <pre>{@code
 * MeterRegistry metrics = ctx.getService(MetricsService.class)
 *                            .orElseThrow()
 *                            .registry();
 *
 * Counter requests = metrics.counter("app.requests", "endpoint", "/api/data");
 * requests.increment();
 * }</pre>
 */
public interface MetricsService extends VService {
    MeterRegistry registry();
}
