package dev.vatn.plugins.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MetricsConfig {

    private final String path;
    private final boolean jvmMetrics;
    private final Map<String, String> globalTags;

    private MetricsConfig(String path, boolean jvmMetrics, Map<String, String> globalTags) {
        this.path = path;
        this.jvmMetrics = jvmMetrics;
        this.globalTags = globalTags;
    }

    public static MetricsConfig defaults() {
        return new MetricsConfig("/metrics", true, Collections.emptyMap());
    }

    public MetricsConfig withPath(String path) {
        return new MetricsConfig(path, jvmMetrics, globalTags);
    }

    public MetricsConfig withoutJvmMetrics() {
        return new MetricsConfig(path, false, globalTags);
    }

    public MetricsConfig withTag(String key, String value) {
        Map<String, String> tags = new LinkedHashMap<>(globalTags);
        tags.put(key, value);
        return new MetricsConfig(path, jvmMetrics, tags);
    }

    public String getPath()                         { return path; }
    public boolean isJvmMetrics()                   { return jvmMetrics; }
    public Map<String, String> getGlobalTags()       { return globalTags; }
}
