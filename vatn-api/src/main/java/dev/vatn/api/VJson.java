package dev.vatn.api;

import java.util.List;

/**
 * Universal JSON service for the VATN platform.
 * Provides high-level abstractions for serialization and parsing,
 * ensuring plugins can handle data without importing Jackson or Gson directly.
 */
@VatnApi(since = "1.0")
public interface VJson extends VService {

    /** Serializes an object to a JSON string. */
    String stringify(Object object);

    /** Parses a JSON string into a typed object. */
    <T> T parse(String json, Class<T> type);

    /** Merges two JSON strings, with override taking precedence on conflicts. */
    String merge(String baseJson, String overrideJson);

    /**
     * Extracts a value at a dot-notation path (e.g. {@code "user.profile.name"}) as the given type.
     * Returns {@code null} if the path does not exist.
     */
    <T> T path(String json, String path, Class<T> type);

    /** Streams objects as newline-delimited JSON (NDJSON). */
    void stringifyStream(java.util.Collection<?> objects, java.io.OutputStream out);

    /** Parses a newline-delimited JSON (NDJSON) stream, calling {@code target} per line. */
    <T> void parseStream(java.io.InputStream in, Class<T> type, java.util.function.Consumer<T> target);

    /**
     * Extracts a value at a dot-notation path as a String.
     * Returns {@code null} if the path is absent. Supports optional {@code "$."} prefix.
     */
    String query(String json, String path);

    /**
     * Extracts a string at a dot-notation path, returning {@code defaultValue} if absent.
     */
    default String query(String json, String path, String defaultValue) {
        String v = query(json, path);
        return v != null ? v : defaultValue;
    }

    /**
     * Extracts an integer at a dot-notation path, returning {@code defaultValue} if absent or unparseable.
     */
    default int queryInt(String json, String path, int defaultValue) {
        String v = query(json, path);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Extracts a boolean at a dot-notation path, returning {@code defaultValue} if absent.
     */
    default boolean queryBoolean(String json, String path, boolean defaultValue) {
        String v = query(json, path);
        if (v == null || v.isBlank()) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    /**
     * Extracts a JSON array at a dot-notation path as a list of strings.
     * Returns an empty list if absent or not an array.
     */
    List<String> queryArray(String json, String path);
}
