package dev.vatn.plugins.containers;

import java.util.List;
import java.util.Optional;

public interface ContainerStackService {
    List<ContainerStack> list();
    Optional<ContainerStack> get(String id);
    ContainerStack save(ContainerStack stack);
    void delete(String id);
}
