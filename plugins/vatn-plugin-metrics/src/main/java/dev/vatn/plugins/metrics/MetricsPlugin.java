package dev.vatn.plugins.metrics;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prometheus metrics plugin for VATN.
 *
 * <p>Registers a {@link MeterRegistry} in the node context and exposes
 * a {@code /metrics} scrape endpoint in Prometheus text format.
 *
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new MetricsPlugin())            // defaults: /metrics + JVM metrics
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 *
 * // Custom path, no JVM metrics
 * VNodeRunner.create(8080)
 *     .addPlugin(new MetricsPlugin(
 *         MetricsConfig.defaults()
 *             .withPath("/internal/metrics")
 *             .withoutJvmMetrics()))
 *     .start();
 *
 * // Registering custom metrics from your plugin
 * MeterRegistry metrics = ctx.getService(MetricsService.class).orElseThrow().registry();
 * Counter requests = metrics.counter("app.requests.total", "endpoint", "/api/data");
 * requests.increment();
 * }</pre>
 */
public class MetricsPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(MetricsPlugin.class);

    private final MetricsConfig config;

    public MetricsPlugin() {
        this(MetricsConfig.defaults());
    }

    public MetricsPlugin(MetricsConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.metrics"; }
    @Override public String getName()    { return "VATN Metrics Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        if (config.isJvmMetrics()) {
            new JvmMemoryMetrics().bindTo(registry);
            new JvmGcMetrics().bindTo(registry);
            new JvmThreadMetrics().bindTo(registry);
            new ProcessorMetrics().bindTo(registry);
            log.debug("JVM metrics bound to Prometheus registry");
        }

        ctx.registerService(MetricsService.class, () -> registry);
        ctx.registerHealthCheck("metrics", () -> true);

        // Expose /metrics scrape endpoint
        ctx.register(config.getPath(), routes ->
            routes.get("", (req, res) ->
                res.header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                   .send(registry.scrape()))
        );

        log.info("Metrics plugin initialized — scrape endpoint: {}", config.getPath());
    }

    @Override
    public void onShutdown() {}
}
