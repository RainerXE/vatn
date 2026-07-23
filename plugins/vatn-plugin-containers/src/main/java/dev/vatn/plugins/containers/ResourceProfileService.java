package dev.vatn.plugins.containers;

import java.util.List;
import java.util.Optional;

public interface ResourceProfileService {
    List<ResourceProfile> list();
    Optional<ResourceProfile> get(String id);
    ResourceProfile save(ResourceProfile profile);
    void delete(String id);
}
