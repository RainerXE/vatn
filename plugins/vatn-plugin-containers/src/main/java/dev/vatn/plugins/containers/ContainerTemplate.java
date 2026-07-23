package dev.vatn.plugins.containers;

import java.util.List;
import java.util.Map;

public record ContainerTemplate(
    String id,
    String name,
    String description,
    String engine,
    String image,
    String containerName,
    String command,
    String entrypoint,
    List<String> ports,
    List<String> volumes,
    Map<String, String> env,
    Map<String, String> labels,
    String networkMode,
    String restartPolicy,
    String workDir,
    String resourceProfileId,
    List<String> postStartCommands,
    int postStartWaitMs,
    long createdAt
) {
    public ContainerTemplate {
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (ports == null) ports = List.of();
        if (volumes == null) volumes = List.of();
        if (env == null) env = Map.of();
        if (labels == null) labels = Map.of();
        if (resourceProfileId == null) resourceProfileId = "";
        if (postStartCommands == null) postStartCommands = List.of();
    }
}
