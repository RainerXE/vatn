package dev.vatn.core;

import dev.vatn.api.VRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process token-bucket rate limiter.
 *
 * <p>Each configured key gets an independent bucket refilled at the configured rate.
 * Refill is computed lazily on each {@link #tryAcquire} call using nanosecond wall-clock
 * time — no background thread required.
 */
public class VTokenBucketRateLimiter implements VRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(VTokenBucketRateLimiter.class);

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) return true; // no limit configured for this key
        return bucket.tryConsume();
    }

    @Override
    public void configure(String key, int requestsPerSecond) {
        if (requestsPerSecond <= 0) {
            buckets.remove(key);
            log.debug("[RATE-LIMITER] Removed limit for key '{}'", key);
        } else {
            buckets.put(key, new TokenBucket(requestsPerSecond));
            log.info("[RATE-LIMITER] Configured '{}' at {} req/s", key, requestsPerSecond);
        }
    }

    // ── token bucket ──────────────────────────────────────────────────────────

    private static final class TokenBucket {
        private final int rps;
        private final long refillNanos;     // nanoseconds per token
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int rps) {
            this.rps = rps;
            this.refillNanos = 1_000_000_000L / rps;
            this.tokens = rps;              // start full
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                double newTokens = (double) elapsed / refillNanos;
                tokens = Math.min(rps, tokens + newTokens);
                lastRefillNanos = now;
            }
        }
    }
}
