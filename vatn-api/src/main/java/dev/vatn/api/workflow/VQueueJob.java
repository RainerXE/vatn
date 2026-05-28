package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.time.Instant;

/**
 * A single job entry in a {@link VNamedQueue}.
 *
 * <p>Jobs are immutable snapshots of a row in {@code vatn_named_queue_jobs} at the moment of
 * claim. Mutating state (ack/nack) is done via the queue handle, not this record.
 */
@VatnApi(since = "1.0-alpha.9")
public record VQueueJob(
        String   id,
        String   queue,
        String   payload,
        int      priority,
        Instant  runAt,
        int      attempts,
        State    state,
        String   workerId,
        Instant  claimExpiresAt,
        String   error,
        String   result,
        Instant  createdAt
) {
    public enum State {
        /** Waiting to be claimed. */
        PENDING,
        /** Claimed by a worker; held until acked, nacked, or visibility timeout expires. */
        CLAIMED,
        /** Successfully acknowledged. */
        DONE,
        /** Nacked and will be retried (attempts < maxAttempts). */
        FAILED,
        /** Exhausted all retry attempts; moved to dead-letter queue (if configured). */
        DEAD
    }
}
