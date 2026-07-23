package dev.vatn.plugins.containers;

public record HealthProbe(
    String type,
    String value,
    int intervalMs,
    int timeoutMs,
    int retries,
    int startPeriodMs
) {
    public HealthProbe {
        if (type == null) type = "exec";
        if (intervalMs <= 0) intervalMs = 5000;
        if (timeoutMs <= 0) timeoutMs = 3000;
        if (retries <= 0) retries = 3;
        if (startPeriodMs <= 0) startPeriodMs = 5000;
    }
}
