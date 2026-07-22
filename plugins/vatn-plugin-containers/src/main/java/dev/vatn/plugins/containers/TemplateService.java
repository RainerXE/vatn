package dev.vatn.plugins.containers;

import dev.vatn.api.VService;
import java.util.List;
import java.util.Optional;

public interface TemplateService extends VService {
    ContainerTemplate save(ContainerTemplate template);
    Optional<ContainerTemplate> get(String id);
    List<ContainerTemplate> list();
    void delete(String id);
}
