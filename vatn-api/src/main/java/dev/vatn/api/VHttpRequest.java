package dev.vatn.api;

import java.util.Optional;

/**
 * Transport-neutral HTTP request. Extends VRequest with HTTP-specific accessors.
 * Implementations are provided by vatn-core (Helidon) or any future runtime adapter.
 */
@VatnApi(since = "1.0")
public interface VHttpRequest extends VRequest {

    /** Returns a path template parameter, e.g. for path "/users/{id}" returns the id value. */
    String getPathParam(String name);

    /** Returns a query string parameter, or null if absent. */
    default String getQueryParam(String name) {
        return getQueryParam(name, null);
    }

    /** Returns a query string parameter, or {@code defaultValue} if absent. */
    String getQueryParam(String name, String defaultValue);

    /** Returns the HTTP method (GET, POST, PUT, DELETE, …). */
    String getMethod();

    /** Returns the request path. */
    String getPath();

    /**
     * Stores a per-request attribute. Filters use this to pass derived context
     * (e.g. an {@code AuthContext}) to downstream filters and route handlers.
     */
    void setAttribute(String key, Object value);

    /**
     * Retrieves a per-request attribute previously stored by a filter.
     * Returns an empty {@link Optional} if the key is absent or the value
     * cannot be cast to {@code type}.
     */
    <T> Optional<T> getAttribute(String key, Class<T> type);
}
