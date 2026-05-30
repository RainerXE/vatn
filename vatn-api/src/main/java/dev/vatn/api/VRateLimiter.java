package dev.vatn.api;

import java.time.Duration;

/**
 * Token-bucket rate limiter SPI.
 *
 * <p>Keys are arbitrary strings — typically HTTP route patterns, plugin type strings, or
 * outbound upstream identifiers (e.g. {@code "out:hardcover"}, {@code "out:googlebooks"}).
 * A key that has no explicit limit is allowed through unconditionally. Configure limits with
 * {@link #configure(String, int)} (per-second) or {@link #configure(String, int, Duration)}
 * (arbitrary window) before the first call to {@link #tryAcquire(String)}.
 *
 * <h3>Inbound — protect a route</h3>
 * <pre>
 *   VRateLimiter limiter = context.getService(VRateLimiter.class).orElseThrow();
 *   limiter.configure("/api/tool-call", 20);     // 20 req/s
 *   if (!limiter.tryAcquire("/api/tool-call")) {
 *       res.status(429).send();
 *       return;
 *   }
 * </pre>
 *
 * <h3>Outbound — respect an upstream quota</h3>
 * <pre>
 *   limiter.configure("out:hardcover",   60, Duration.ofMinutes(1));   // 60 / min
 *   limiter.configure("out:googlebooks", 1_000, Duration.ofDays(1));   // ~1k / day
 *
 *   // Block until a permit is available, honouring the limiter's own backoff hint.
 *   limiter.acquire("out:hardcover");
 *   httpClient.get(hardcoverUrl);
 * </pre>
 *
 * <p>Inbound vs. outbound is purely a key-naming convention; the same limiter instance serves
 * both. Outbound callers typically prefer {@link #acquire(String)} (blocking) or
 * {@link #millisUntilAvailable(String)} (to schedule a retry) over a bare {@link #tryAcquire}.
 */
@VatnApi(since = "1.1")
public interface VRateLimiter extends VService {

    /**
     * Attempts to acquire one token for the given key.
     *
     * @param key route pattern, plugin type, or outbound upstream identifier
     * @return true if the request is within the configured limit; true if no limit is configured
     */
    boolean tryAcquire(String key);

    /**
     * Attempts to acquire {@code permits} tokens for the given key in a single call.
     *
     * @param key     the limiter key
     * @param permits number of tokens to consume (≥ 1)
     * @return true if all permits were available, false otherwise (no tokens are consumed on failure)
     */
    @VatnApi(since = "1.2")
    default boolean tryAcquire(String key, int permits) {
        // Conservative fallback for older implementations: only succeeds for a single permit.
        if (permits <= 1) return tryAcquire(key);
        throw new UnsupportedOperationException("Multi-permit acquire not supported by this VRateLimiter");
    }

    /**
     * Configures or updates the rate limit for a key, expressed as a sustained per-second rate.
     *
     * @param key               the limiter key
     * @param requestsPerSecond maximum sustained rate; 0 or negative removes the limit
     */
    void configure(String key, int requestsPerSecond);

    /**
     * Configures or updates the rate limit for a key over an arbitrary window.
     *
     * <p>Use this for upstream quotas that are not naturally expressed per second, such as
     * {@code (1000, Duration.ofDays(1))} for "1k requests per day" or
     * {@code (60, Duration.ofMinutes(1))} for "60 per minute". The bucket capacity equals
     * {@code permits}, so a full window's allowance may be spent as an initial burst and then
     * refills smoothly across the window.
     *
     * @param key     the limiter key
     * @param permits maximum number of permits per window; 0 or negative removes the limit
     * @param window  the window the permits are spread over
     */
    @VatnApi(since = "1.2")
    default void configure(String key, int permits, Duration window) {
        // Fallback: approximate as a per-second rate (loses sub-second granularity).
        long seconds = Math.max(1, window.toSeconds());
        configure(key, (int) Math.max(1, permits / seconds));
    }

    /**
     * Blocks the calling (virtual) thread until one permit is available for {@code key}, then
     * consumes it. Returns immediately if no limit is configured.
     *
     * @param key the limiter key
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    @VatnApi(since = "1.2")
    default void acquire(String key) throws InterruptedException {
        while (!tryAcquire(key)) {
            long wait = Math.max(1, millisUntilAvailable(key));
            Thread.sleep(Math.min(wait, 1_000));
        }
    }

    /**
     * Returns the number of milliseconds until at least one permit is expected to be available
     * for {@code key}. Returns 0 when a permit is available now or no limit is configured.
     *
     * <p>Useful for outbound clients that want to schedule a retry rather than block.
     *
     * @param key the limiter key
     * @return milliseconds until the next permit, or 0 if available now
     */
    @VatnApi(since = "1.2")
    default long millisUntilAvailable(String key) {
        return tryAcquire(key) ? 0L : 1_000L;
    }
}
