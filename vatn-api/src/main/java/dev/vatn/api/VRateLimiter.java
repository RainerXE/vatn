package dev.vatn.api;

/**
 * Token-bucket rate limiter SPI.
 *
 * <p>Keys are typically HTTP route patterns or plugin type strings.
 * A key that has no explicit limit is allowed through unconditionally.
 * Configure limits with {@link #configure(String, int)} before the
 * first call to {@link #tryAcquire(String)}.
 *
 * <p>Register as a system service and use from HTTP filters or plugin
 * interceptors:
 * <pre>
 *   VRateLimiter limiter = context.getService(VRateLimiter.class).orElseThrow();
 *   limiter.configure("/api/tool-call", 20);  // 20 req/s
 *   if (!limiter.tryAcquire("/api/tool-call")) {
 *       res.status(429).send();
 *       return;
 *   }
 * </pre>
 */
@VatnApi(since = "1.1")
public interface VRateLimiter extends VService {

    /**
     * Attempts to acquire one token for the given key.
     *
     * @param key route pattern or plugin type string
     * @return true if the request is within the configured limit; true if no limit is configured
     */
    boolean tryAcquire(String key);

    /**
     * Configures or updates the rate limit for a key.
     *
     * @param key               route pattern or plugin type string
     * @param requestsPerSecond maximum sustained rate; 0 or negative removes the limit
     */
    void configure(String key, int requestsPerSecond);
}
