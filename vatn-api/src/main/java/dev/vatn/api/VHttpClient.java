package dev.vatn.api;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Transport-neutral outbound HTTP client SPI.
 * Allows VATN plugins to make HTTP calls without importing java.net.http directly,
 * enabling future sandboxing, rate limiting, SSRF guards, and audit logging.
 */
@VatnApi(since = "1.0")
public interface VHttpClient extends VService {

    /** Response from an outbound HTTP call. */
    record Response(int statusCode, String body) {
        public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }
    }

    // ── Core methods (implementations must provide these) ────────────────────

    Response get(String url, Map<String, String> headers, Duration timeout) throws IOException;

    Response post(String url, String body, String contentType, Map<String, String> headers, Duration timeout) throws IOException;

    // ── Convenience defaults ─────────────────────────────────────────────────

    /** GET with default 30-second timeout. */
    default Response get(String url, Map<String, String> headers) throws IOException {
        return get(url, headers, Duration.ofSeconds(30));
    }

    /** GET with no extra headers and default timeout. */
    default Response get(String url) throws IOException {
        return get(url, Map.of());
    }

    /** GET with custom timeout and no extra headers. */
    default Response get(String url, Duration timeout) throws IOException {
        return get(url, Map.of(), timeout);
    }

    /** POST with default 30-second timeout. */
    default Response post(String url, String body, String contentType, Map<String, String> headers) throws IOException {
        return post(url, body, contentType, headers, Duration.ofSeconds(30));
    }

    /** POST with no extra headers and default timeout. */
    default Response post(String url, String body, String contentType) throws IOException {
        return post(url, body, contentType, Map.of());
    }
}
