package dev.vatn.plugins.devenv.model;

import java.util.List;

/** Container runtimes plus the containers and images of the active engine. */
public record ContainerInventory(
        List<ContainerRuntime> runtimes,
        List<ContainerEntry> containers,
        List<ContainerImage> images) {

    public static ContainerInventory empty() {
        return new ContainerInventory(List.of(), List.of(), List.of());
    }
}
