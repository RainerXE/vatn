package dev.vatn.plugins.containers;

import dev.vatn.api.admin.VWorkload;
import dev.vatn.api.admin.VWorkloadProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ContainersWorkloadProvider implements VWorkloadProvider {

    private final List<ContainerManager> managers;

    public ContainersWorkloadProvider(List<ContainerManager> managers) {
        this.managers = managers;
    }

    @Override
    public List<VWorkload> getActiveWorkloads() {
        return managers.stream()
            .flatMap(manager -> manager.listContainers().stream())
            .filter(VContainer::isRunning)
            .map(c -> new VWorkload(
                c.id(),
                c.name(),
                VWorkload.Type.CONTAINER,
                VWorkload.Status.RUNNING,
                Instant.now(), // Real start time requires deeper inspection, using now as placeholder
                Map.of(
                    "engine", c.engine().name(),
                    "image", c.image()
                )
            ))
            .collect(Collectors.toList());
    }
}
