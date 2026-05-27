package dev.vatn.api;

import java.util.Optional;

/**
 * Interface for accessing node configuration and trust settings.
 */
@VatnApi(since = "1.0")
public interface VConfiguration extends VService {
    
    /**
     * Retrieves a configuration value by key.
     */
    Optional<String> get(String key);
    
    /**
     * Returns true if the node is running in AOT (Native Image) mode.
     */
    boolean isAot();

    /**
     * Returns the global default trust level for unverified plugins.
     */
    String getDefaultTrustLevel();
}
