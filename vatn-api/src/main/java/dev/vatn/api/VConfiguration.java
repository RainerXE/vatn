package dev.vatn.api;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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

    /**
     * Returns the value for {@code key}, throwing {@link IllegalStateException} if absent.
     * Use for required configuration that must be present at startup.
     */
    default String getRequired(String key) {
        return get(key).orElseThrow(() ->
                new IllegalStateException("Required configuration key missing: " + key));
    }

    /** Returns the value for {@code key} as an int, or {@code defaultValue} if absent or non-numeric. */
    default int getInt(String key, int defaultValue) {
        return get(key).map(v -> {
            try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
        }).orElse(defaultValue);
    }

    /** Returns the value for {@code key} as a long, or {@code defaultValue} if absent or non-numeric. */
    default long getLong(String key, long defaultValue) {
        return get(key).map(v -> {
            try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
        }).orElse(defaultValue);
    }

    /** Returns the value for {@code key} as a double, or {@code defaultValue} if absent or non-numeric. */
    default double getDouble(String key, double defaultValue) {
        return get(key).map(v -> {
            try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
        }).orElse(defaultValue);
    }

    /** Returns true if the value for {@code key} equals {@code "true"} (case-insensitive). */
    default boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(v -> Boolean.parseBoolean(v.trim())).orElse(defaultValue);
    }

    /**
     * Returns the value for {@code key} as a list, split on commas and trimmed.
     * Returns an empty list if the key is absent.
     * Example: {@code "redis,postgres,s3"} → {@code ["redis", "postgres", "s3"]}.
     */
    default List<String> getList(String key) {
        return get(key)
                .filter(v -> !v.isBlank())
                .map(v -> Arrays.stream(v.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList()))
                .orElse(List.of());
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
