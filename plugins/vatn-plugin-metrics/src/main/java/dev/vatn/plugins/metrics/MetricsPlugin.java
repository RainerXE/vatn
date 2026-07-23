package dev.vatn.plugins.metrics;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(MetricsPlugin.class);

    private final MetricsConfig config;
    private MetricsServiceImpl metricsService;

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
        metricsService = new MetricsServiceImpl(config);

        if (config.isJvmMetrics()) {
            new JvmMemoryMetrics().bindTo(metricsService.registry());
            new JvmGcMetrics().bindTo(metricsService.registry());
            new JvmThreadMetrics().bindTo(metricsService.registry());
            new ProcessorMetrics().bindTo(metricsService.registry());
            log.debug("JVM metrics bound to Prometheus registry");
        }

        ctx.registerService(MetricsService.class, metricsService);
        ctx.registerHealthCheck("metrics", () -> metricsService.registry() != null);

        var service = metricsService;
        ctx.register(config.getPath(), routes ->
            routes.get("", (req, res) ->
                res.header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                   .send(service.scrape()))
        );

        log.info("Metrics plugin initialized — scrape endpoint: {}", config.getPath());
    }

    @Override
    public void onShutdown() {}
}
