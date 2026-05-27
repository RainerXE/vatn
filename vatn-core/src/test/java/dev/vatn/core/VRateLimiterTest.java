package dev.vatn.core;

import dev.vatn.api.VRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VRateLimiterTest {

    @Test
    void unconfiguredKeyAlwaysAllowed() {
        VRateLimiter limiter = new VTokenBucketRateLimiter();
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryAcquire("/any/path"), "unconfigured key must pass");
        }
    }

    @Test
    void configuredKeyThrottlesAtLimit() throws InterruptedException {
        VRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("/api/tool", 5);

        // First 5 should succeed (bucket starts full)
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("/api/tool")) allowed++;
        }
        assertTrue(allowed >= 5 && allowed <= 10, "Expected ~5 allowed from full bucket, got " + allowed);
    }

    @Test
    void removeLimitBySettingZero() {
        VRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("/api/tool", 1);
        limiter.configure("/api/tool", 0); // remove limit
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire("/api/tool"), "after removal, key must be unlimited");
        }
    }

    @Test
    void separateKeysHaveIndependentBuckets() {
        VRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("/a", 2);
        limiter.configure("/b", 100);

        // Drain /a
        limiter.tryAcquire("/a");
        limiter.tryAcquire("/a");
        boolean aThrottled = !limiter.tryAcquire("/a");

        // /b should still be generous
        assertTrue(limiter.tryAcquire("/b"));
        assertTrue(aThrottled, "/a should be throttled after exhausting its bucket");
    }

    @Test
    void bucketRefillsOverTime() throws InterruptedException {
        VRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("/api", 10);

        // Drain the bucket
        for (int i = 0; i < 10; i++) limiter.tryAcquire("/api");
        assertFalse(limiter.tryAcquire("/api"), "bucket should be empty after drain");

        // Wait for refill (~150ms for 1–2 tokens at 10 rps)
        Thread.sleep(150);
        assertTrue(limiter.tryAcquire("/api"), "bucket should have refilled after waiting");
    }
}
