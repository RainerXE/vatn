package dev.vatn.core;

import dev.vatn.api.VRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process token-bucket rate limiter.
 *
 * <p>Each configured key gets an independent bucket refilled at the configured rate.
 * Refill is computed lazily on each {@link #tryAcquire} call using nanosecond wall-clock
 * time — no background thread required.
 *
 * <p>Supports both per-second limits and arbitrary windows (e.g. 1000/day, 60/min) via
 * {@link #configure(String, int, Duration)}. The bucket capacity equals the window's permit
 * count, so a full window allowance may be consumed as an initial burst, then refills smoothly.
 */
public class VTokenBucketRateLimiter implements VRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(VTokenBucketRateLimiter.class);

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    @Override
    public boolean tryAcquire(String key, int permits) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) return true; // no limit configured for this key
        return bucket.tryConsume(Math.max(1, permits));
    }

    @Override
    public void configure(String key, int requestsPerSecond) {
        if (requestsPerSecond <= 0) {
            buckets.remove(key);
            log.debug("[RATE-LIMITER] Removed limit for key '{}'", key);
        } else {
            buckets.put(key, new TokenBucket(requestsPerSecond, Duration.ofSeconds(1)));
            log.info("[RATE-LIMITER] Configured '{}' at {} req/s", key, requestsPerSecond);
        }
    }

    @Override
    public void configure(String key, int permits, Duration window) {
        if (permits <= 0 || window == null || window.isZero() || window.isNegative()) {
            buckets.remove(key);
            log.debug("[RATE-LIMITER] Removed limit for key '{}'", key);
        } else {
            buckets.put(key, new TokenBucket(permits, window));
            log.info("[RATE-LIMITER] Configured '{}' at {} permits / {}", key, permits, window);
        }
    }

    @Override
    public long millisUntilAvailable(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) return 0L;
        return bucket.millisUntilAvailable(1);
    }

    // ── token bucket ──────────────────────────────────────────────────────────

    private static final class TokenBucket {
        private final double capacity;       // max tokens (= permits per window)
        private final double refillPerNano;  // tokens regenerated per nanosecond
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int permits, Duration window) {
            this.capacity = permits;
            long windowNanos = Math.max(1L, window.toNanos());
            this.refillPerNano = (double) permits / windowNanos;
            this.tokens = permits;          // start full
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(int permits) {
            refill();
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        }

        synchronized long millisUntilAvailable(int permits) {
            refill();
            if (tokens >= permits) return 0L;
            double deficit = permits - tokens;
            double nanos = deficit / refillPerNano;
            return (long) Math.ceil(nanos / 1_000_000.0);
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
                lastRefillNanos = now;
            }
        }
    }
}
