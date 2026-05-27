package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Retry configuration for a task instance.
 * Uses exponential back-off capped at {@code maxDelayMs}.
 *
 * @param maxAttempts       Total number of attempts (including the first). 1 = no retry.
 * @param initialDelayMs    Delay before the first retry, in milliseconds.
 * @param backoffMultiplier Multiplier applied to delay after each failure (e.g., 2.0 = doubling).
 * @param maxDelayMs        Upper ceiling on the computed delay, in milliseconds.
 */
@VatnApi(since = "1.0")
public record VRetryPolicy(int maxAttempts, long initialDelayMs, double backoffMultiplier, long maxDelayMs) {

    /** No retries — fail immediately on first error. */
    public static final VRetryPolicy NONE = new VRetryPolicy(1, 0, 1.0, 0);

    /** Three attempts with 10-second initial delay, doubling up to 5 minutes. */
    public static final VRetryPolicy DEFAULT = new VRetryPolicy(3, 10_000, 2.0, 300_000);

    /**
     * Computes the delay for attempt {@code attemptNumber} (1-based: 1 = first retry).
     *
     * @param attemptNumber the 1-based retry attempt number
     * @return delay in milliseconds
     */
    public long delayForAttempt(int attemptNumber) {
        if (maxAttempts <= 1) return 0;
        long delay = initialDelayMs;
        for (int i = 1; i < attemptNumber; i++) {
            delay = (long) (delay * backoffMultiplier);
        }
        return Math.min(delay, maxDelayMs);
    }
}
