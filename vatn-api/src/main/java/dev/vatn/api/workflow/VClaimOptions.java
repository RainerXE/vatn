package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.time.Duration;

/**
 * Configuration for claim/ack behaviour on a {@link VNamedQueue}.
 *
 * <p>Visibility timeout: after a job is claimed, if the worker does not ack or nack within
 * {@code visibilityTimeout}, the job reverts to PENDING and becomes available for another worker.
 * This ensures crashed workers don't permanently lose jobs.
 *
 * <p>Dead-letter queue: when a job exceeds {@code maxAttempts}, it is moved to the named
 * dead-letter queue (as a PENDING job there) rather than left as DEAD in the source queue.
 *
 * <pre>{@code
 * VClaimOptions opts = VClaimOptions.defaults()
 *     .withVisibility(Duration.ofMinutes(10))
 *     .withMaxAttempts(5)
 *     .withBackoff(Duration.ofSeconds(60))
 *     .withDeadLetterQueue("emails.dlq");
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.9")
public final class VClaimOptions {

    private final Duration visibilityTimeout;
    private final int      maxAttempts;
    private final Duration backoff;
    private final String   deadLetterQueue;
    private final int      pollIntervalMs;
    private final int      batchSize;

    private VClaimOptions(Duration visibilityTimeout, int maxAttempts, Duration backoff,
                          String deadLetterQueue, int pollIntervalMs, int batchSize) {
        this.visibilityTimeout = visibilityTimeout;
        this.maxAttempts       = maxAttempts;
        this.backoff           = backoff;
        this.deadLetterQueue   = deadLetterQueue;
        this.pollIntervalMs    = pollIntervalMs;
        this.batchSize         = batchSize;
    }

    /** Sensible defaults: 5-minute visibility, 3 attempts, 30-second backoff, no DLQ. */
    public static VClaimOptions defaults() {
        return new VClaimOptions(Duration.ofMinutes(5), 3, Duration.ofSeconds(30), null, 200, 1);
    }

    public VClaimOptions withVisibility(Duration d)         { return new VClaimOptions(d, maxAttempts, backoff, deadLetterQueue, pollIntervalMs, batchSize); }
    public VClaimOptions withMaxAttempts(int n)             { return new VClaimOptions(visibilityTimeout, n, backoff, deadLetterQueue, pollIntervalMs, batchSize); }
    public VClaimOptions withBackoff(Duration d)            { return new VClaimOptions(visibilityTimeout, maxAttempts, d, deadLetterQueue, pollIntervalMs, batchSize); }
    public VClaimOptions withDeadLetterQueue(String queue)  { return new VClaimOptions(visibilityTimeout, maxAttempts, backoff, queue, pollIntervalMs, batchSize); }
    public VClaimOptions withPollIntervalMs(int ms)         { return new VClaimOptions(visibilityTimeout, maxAttempts, backoff, deadLetterQueue, ms, batchSize); }
    public VClaimOptions withBatchSize(int n)               { return new VClaimOptions(visibilityTimeout, maxAttempts, backoff, deadLetterQueue, pollIntervalMs, n); }

    public Duration visibilityTimeout() { return visibilityTimeout; }
    public int      maxAttempts()       { return maxAttempts; }
    public Duration backoff()           { return backoff; }
    public String   deadLetterQueue()   { return deadLetterQueue; }
    public int      pollIntervalMs()    { return pollIntervalMs; }
    public int      batchSize()         { return batchSize; }
}
