package dev.vatn.plugins.containers;

import java.util.List;
import java.util.Map;

public record StackService(
    String name,
    String templateId,
    List<ServiceDependency> dependsOn,
    HealthProbe healthProbe,
    Map<String, String> env
) {
    public StackService {
        if (dependsOn == null) dependsOn = List.of();
        if (env == null) env = Map.of();
    }
}
