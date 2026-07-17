package dev.vatn.plugins.containers;

import java.util.Map;

public record VContainer(
    String id,
    String name,
    VContainerEngine engine,
    String image,
    String status,
    boolean isRunning,
    Map<String, String> labels
) {
}
