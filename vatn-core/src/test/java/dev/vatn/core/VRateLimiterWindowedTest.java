package dev.vatn.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class VRateLimiterWindowedTest {

    @Test
    void perSecondLimitBlocks() {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("route", 2); // 2 req/s

        assertTrue(limiter.tryAcquire("route"));
        assertTrue(limiter.tryAcquire("route"));
        assertFalse(limiter.tryAcquire("route"), "third request must be rejected");
    }

    @Test
    void windowedLimitWithLargeWindow() {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        // 3 permits per hour — burst of 3 allowed immediately, 4th denied
        limiter.configure("out:api", 3, Duration.ofHours(1));

        assertTrue(limiter.tryAcquire("out:api"));
        assertTrue(limiter.tryAcquire("out:api"));
        assertTrue(limiter.tryAcquire("out:api"));
        assertFalse(limiter.tryAcquire("out:api"), "4th must be denied");
    }

    @Test
    void multiPermitAcquire() {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("bulk", 10, Duration.ofSeconds(1));

        assertTrue(limiter.tryAcquire("bulk", 5));
        assertTrue(limiter.tryAcquire("bulk", 5));
        assertFalse(limiter.tryAcquire("bulk", 1), "bucket exhausted");
    }

    @Test
    void unconfiguredKeyAlwaysPasses() {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire("unknown-key"));
        }
    }

    @Test
    void millisUntilAvailableReturnsZeroWhenFree() {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("key", 10, Duration.ofSeconds(1));
        assertEquals(0, limiter.millisUntilAvailable("key"));
    }

    @Test
    void millisUntilAvailablePositiveWhenExhausted() {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("key", 1, Duration.ofSeconds(1));
        limiter.tryAcquire("key");
        assertTrue(limiter.millisUntilAvailable("key") > 0);
    }

    @Test
    void removeLimitByZero() {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("key", 1);
        limiter.tryAcquire("key");
        assertFalse(limiter.tryAcquire("key"));

        limiter.configure("key", 0); // remove
        assertTrue(limiter.tryAcquire("key"), "after removal all requests pass");
    }

    @Test
    void refillOverTime() throws Exception {
        VTokenBucketRateLimiter limiter = new VTokenBucketRateLimiter();
        limiter.configure("fast", 100, Duration.ofMillis(100)); // 100 per 100 ms
        for (int i = 0; i < 100; i++) limiter.tryAcquire("fast"); // drain
        Thread.sleep(110); // wait one window
        assertTrue(limiter.tryAcquire("fast"), "bucket should have refilled");
    }
}
