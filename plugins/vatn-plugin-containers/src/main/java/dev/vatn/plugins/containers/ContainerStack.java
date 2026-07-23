package dev.vatn.plugins.containers;

import java.util.List;

public record ContainerStack(
    String id,
    String name,
    String description,
    List<StackService> services,
    long createdAt
) {
    public ContainerStack {
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (services == null) services = List.of();
    }
}
