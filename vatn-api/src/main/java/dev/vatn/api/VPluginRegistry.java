package dev.vatn.api;

import java.util.List;

/**
 * Service for discovery and management of plugins within a VATN node.
 */
@VatnApi(since = "1.0")
public interface VPluginRegistry extends VService {
    
    /**
     * Returns a list of all plugins currently loaded in this node.
     */
    List<VNodePlugin> getPlugins();
    
    /**
     * Checks if a specific plugin is loaded.
     */
    default boolean isLoaded(String pluginId) {
        return getPlugins().stream().anyMatch(p -> p.getId().equals(pluginId));
    }
}
