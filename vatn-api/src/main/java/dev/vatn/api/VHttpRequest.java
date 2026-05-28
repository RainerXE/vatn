package dev.vatn.api;

import java.util.Map;
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

    /** Returns the raw request body as bytes (e.g. for binary uploads or signature verification). */
    byte[] getBodyBytes();

    /**
     * Returns all request headers as a map. Multi-value headers are collapsed to their
     * first value. Header names are lower-cased for case-insensitive lookup.
     */
    Map<String, String> getHeaders();

    // ── convenient defaults ───────────────────────────────────────────────────

    /** Returns the {@code Content-Type} header value, or {@code null} if absent. */
    default String getContentType() {
        return getHeader("Content-Type");
    }

    /**
     * Returns the value of a request cookie by name, or {@code null} if absent.
     * Parses the {@code Cookie} header inline — no additional overhead when unused.
     */
    default String getCookie(String name) {
        String cookieHeader = getHeader("Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) return null;
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).trim().equals(name)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /**
     * Returns a form field value from an {@code application/x-www-form-urlencoded} body,
     * or {@code null} if the field is absent or the content type is not form-encoded.
     */
    default String getFormParam(String name) {
        String ct = getContentType();
        if (ct == null || !ct.contains("application/x-www-form-urlencoded")) return null;
        String body = getBody();
        if (body == null || body.isBlank()) return null;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                if (k.equals(name)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

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
