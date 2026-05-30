package dev.vatn.api;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Transport-neutral outbound HTTP client SPI.
 * Allows VATN plugins to make HTTP calls without importing java.net.http directly,
 * enabling future sandboxing, rate limiting, SSRF guards, and audit logging.
 *
 * <p>A resilient variant — retry/backoff, ETag/TTL response caching, and a circuit breaker —
 * is available in the runtime and configured with {@link RetryPolicy}, {@link CachePolicy},
 * and {@link CircuitBreakerPolicy}. Resolve the configured client with
 * {@code ctx.getService(VHttpClient.class)}; the runtime registers a resilient implementation
 * by default.
 */
@VatnApi(since = "1.0")
public interface VHttpClient extends VService {

    /** Response from an outbound HTTP call. */
    record Response(int statusCode, String body, Map<String, List<String>> headers) {

        /** Back-compatible constructor for callers that do not care about response headers. */
        public Response(int statusCode, String body) {
            this(statusCode, body, Map.of());
        }

        public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }

        /** Case-insensitive lookup of the first value for a response header. */
        public Optional<String> header(String name) {
            if (headers == null) return Optional.empty();
            String want = name.toLowerCase(Locale.ROOT);
            return headers.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(want))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst();
        }
    }

    // ── Resilience policies (consumed by the runtime's resilient client) ─────────

    /**
     * Retry-with-backoff policy for transient failures (IOException, 429, 5xx).
     *
     * @param maxAttempts   total attempts including the first (1 disables retries)
     * @param baseDelay     initial backoff delay
     * @param maxDelay      cap on the backoff delay
     * @param multiplier    exponential backoff multiplier applied per attempt
     * @param jitter        if true, randomises each delay in {@code [0.5x, 1.0x]} to avoid thundering herds
     * @param honorRetryAfter if true, a {@code Retry-After} response header overrides the computed delay
     */
    @VatnApi(since = "1.2")
    record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay,
                       double multiplier, boolean jitter, boolean honorRetryAfter) {
        public static RetryPolicy none() {
            return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0, false, false);
        }
        /** Sensible default: 3 attempts, 250 ms base, 10 s cap, 2x backoff with jitter, honour Retry-After. */
        public static RetryPolicy defaults() {
            return new RetryPolicy(3, Duration.ofMillis(250), Duration.ofSeconds(10), 2.0, true, true);
        }
    }

    /**
     * ETag + TTL response cache policy for idempotent GETs.
     *
     * @param enabled       master switch
     * @param ttl           how long a cached response is served without revalidation
     * @param maxEntries    maximum number of cached responses (LRU eviction beyond this)
     * @param respectServerCaching if true, honours {@code Cache-Control: no-store} / {@code max-age}
     */
    @VatnApi(since = "1.2")
    record CachePolicy(boolean enabled, Duration ttl, int maxEntries, boolean respectServerCaching) {
        public static CachePolicy disabled() {
            return new CachePolicy(false, Duration.ZERO, 0, true);
        }
        /** Sensible default: enabled, 5-minute TTL, 1024 entries, server caching respected. */
        public static CachePolicy defaults() {
            return new CachePolicy(true, Duration.ofMinutes(5), 1024, true);
        }
    }

    /**
     * Per-host circuit breaker policy.
     *
     * @param enabled          master switch
     * @param failureThreshold consecutive failures that trip the breaker OPEN
     * @param openDuration     how long the breaker stays OPEN before a HALF_OPEN trial
     */
    @VatnApi(since = "1.2")
    record CircuitBreakerPolicy(boolean enabled, int failureThreshold, Duration openDuration) {
        public static CircuitBreakerPolicy disabled() {
            return new CircuitBreakerPolicy(false, 0, Duration.ZERO);
        }
        /** Sensible default: trip after 5 consecutive failures, stay open 30 s. */
        public static CircuitBreakerPolicy defaults() {
            return new CircuitBreakerPolicy(true, 5, Duration.ofSeconds(30));
        }
    }

    /** Thrown when a request is rejected because the per-host circuit breaker is OPEN. */
    @VatnApi(since = "1.2")
    class CircuitOpenException extends IOException {
        public CircuitOpenException(String message) { super(message); }
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
