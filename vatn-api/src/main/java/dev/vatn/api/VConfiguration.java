package dev.vatn.api;

import java.util.Optional;

/**
 * Interface for accessing node configuration and trust settings.
 */
@VatnApi(since = "1.0")
public interface VConfiguration extends VService {
    
    /**
     * Retrieves a configuration value by key. Checks environment variables
     * and any config file loaded at startup, in that order.
     */
    Optional<String> get(String key);

    /** Returns the value for {@code key}, or {@code defaultValue} if absent. */
    default String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /** Returns the value for {@code key} as an int, or {@code defaultValue} if absent or non-numeric. */
    default int getInt(String key, int defaultValue) {
        return get(key).map(v -> {
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
        }).orElse(defaultValue);
    }

    /** Returns true if the value for {@code key} equals {@code "true"} (case-insensitive). */
    default boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(v -> Boolean.parseBoolean(v)).orElse(defaultValue);
    }
    
    /**
     * Returns true if the node is running in AOT (Native Image) mode.
     */
    boolean isAot();

    /**
     * Returns the global default trust level for unverified plugins.
     */
    String getDefaultTrustLevel();
}
