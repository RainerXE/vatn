package dev.vatn.plugins.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.util.List;

public class MetricsServiceImpl implements MetricsService {
    private final PrometheusMeterRegistry registry;

    public MetricsServiceImpl(MetricsConfig config) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        if (config.getGlobalTags() != null && !config.getGlobalTags().isEmpty()) {
            List<Tag> tags = config.getGlobalTags().entrySet().stream()
                    .map(e -> Tag.of(e.getKey(), e.getValue()))
                    .toList();
            registry.config().commonTags(tags);
        }
    }

    @Override
    public PrometheusMeterRegistry registry() {
        return registry;
    }

    public String scrape() {
        return registry.scrape();
    }
}
