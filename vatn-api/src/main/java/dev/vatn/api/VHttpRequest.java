package dev.vatn.api;

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
}
