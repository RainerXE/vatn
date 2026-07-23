package dev.vatn.plugins.containers;

import java.util.List;

public record ResourceProfile(
    String id,
    String name,
    String description,
    String cpuMin,
    String cpuMax,
    String memoryMin,
    String memoryMax,
    List<String> deviceMounts,
    String gpuMode,
    String extraCliArgs,
    long createdAt
) {
    public ResourceProfile {
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (deviceMounts == null) deviceMounts = List.of();
        if (gpuMode == null) gpuMode = "none";
        if (extraCliArgs == null) extraCliArgs = "";
    }
}
