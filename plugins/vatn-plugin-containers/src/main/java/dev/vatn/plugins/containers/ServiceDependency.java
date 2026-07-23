package dev.vatn.plugins.containers;

public record ServiceDependency(
    String service,
    String condition
) {
    public ServiceDependency {
        if (condition == null) condition = "healthy";
    }
}
