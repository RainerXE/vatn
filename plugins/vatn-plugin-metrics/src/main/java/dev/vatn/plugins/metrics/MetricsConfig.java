package dev.vatn.plugins.metrics;

/** Configuration for the Prometheus metrics plugin. */
public final class MetricsConfig {

    private final String path;
    private final boolean jvmMetrics;

    private MetricsConfig(String path, boolean jvmMetrics) {
        this.path = path;
        this.jvmMetrics = jvmMetrics;
    }

    /** Default config: {@code /metrics} endpoint with JVM metrics enabled. */
    public static MetricsConfig defaults() {
        return new MetricsConfig("/metrics", true);
    }

    /** Override the scrape path (default {@code /metrics}). */
    public MetricsConfig withPath(String path) {
        return new MetricsConfig(path, jvmMetrics);
    }

    /** Disable automatic JVM memory, GC, thread and CPU metrics. */
    public MetricsConfig withoutJvmMetrics() {
        return new MetricsConfig(path, false);
    }

    public String getPath()       { return path; }
    public boolean isJvmMetrics() { return jvmMetrics; }
}
