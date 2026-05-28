package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A named, durable, at-least-once work queue backed by the node's SQLite database.
 *
 * <h3>Producer side</h3>
 * <pre>{@code
 * VNamedQueue emails = ctx.getService(VQueueService.class).orElseThrow().queue("emails");
 *
 * // simple enqueue
 * String id = emails.enqueue("{\"to\":\"alice@example.com\"}");
 *
 * // delayed job (run no sooner than 10 minutes from now)
 * emails.enqueueAt("{\"to\":\"bob@example.com\"}", Instant.now().plusSeconds(600));
 *
 * // high priority
 * emails.enqueue("{\"to\":\"ceo@example.com\"}", 10);
 *
 * // atomic with a business write — same Connection, same SQLite transaction
 * emails.enqueue("{\"to\":\"alice@example.com\"}", existingConnection);
 * }</pre>
 *
 * <h3>Consumer side — blocking iterator pattern</h3>
 * <pre>{@code
 * // starts a background virtual thread; returns immediately
 * emails.consume("worker-1", job -> {
 *     sendEmail(job.payload());
 *     job.ack();
 * });
 * }</pre>
 *
 * <h3>Consumer side — manual batch claim/ack</h3>
 * <pre>{@code
 * List<VQueueJob> batch = emails.claimBatch("worker-1", 32);
 * batch.parallelStream().forEach(job -> {
 *     try {
 *         sendEmail(job.payload());
 *         emails.ack(job.id(), "worker-1");
 *     } catch (Exception e) {
 *         emails.nack(job.id(), "worker-1", e.getMessage());
 *     }
 * });
 * }</pre>
 *
 * <h3>Result storage</h3>
 * <pre>{@code
 * // producer stores a result after processing
 * emails.ack(jobId, "worker-1", "{\"sentAt\":\"2026-01-01T00:00:00Z\"}");
 *
 * // caller waits for result (blocks the calling virtual thread)
 * Optional<String> result = emails.waitResult(jobId, Duration.ofSeconds(30));
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.9")
public interface VNamedQueue {

    /** The queue name this handle was opened for. */
    String name();

    // ── Enqueue ───────────────────────────────────────────────────────────────

    /** Enqueues a JSON payload with default priority (0). Returns the job ID. */
    String enqueue(String payload);

    /** Enqueues with an explicit priority — higher values are claimed first. */
    String enqueue(String payload, int priority);

    /** Enqueues to run no earlier than {@code runAt}. */
    String enqueueAt(String payload, Instant runAt);

    /** Enqueues to run no earlier than {@code runAt} with explicit priority. */
    String enqueueAt(String payload, Instant runAt, int priority);

    /**
     * Enqueues atomically within an existing JDBC transaction.
     * The caller is responsible for committing or rolling back {@code tx}.
     * Combine with {@code VPersistenceService.getConnection()} to bundle a business write
     * and a queue enqueue in a single SQLite transaction.
     */
    String enqueue(String payload, Connection tx);

    // ── Consume (background auto-ack loop) ───────────────────────────────────

    /**
     * Starts a background virtual thread that continuously claims one job at a time,
     * calls {@code handler}, and acks on success or nacks on exception.
     * Returns immediately; the loop runs until the node shuts down or the thread is interrupted.
     *
     * <p>Uses the queue's default {@link VClaimOptions}.
     */
    void consume(String workerId, JobConsumer handler);

    /** Same as {@link #consume(String, JobConsumer)} with explicit claim options. */
    void consume(String workerId, JobConsumer handler, VClaimOptions options);

    // ── Manual claim / ack ────────────────────────────────────────────────────

    /**
     * Claims up to {@code maxJobs} jobs atomically. Claimed jobs are invisible to other
     * workers until the visibility timeout expires or they are acked/nacked.
     * Returns an empty list if the queue is empty or all pending jobs have a future {@code run_at}.
     */
    List<VQueueJob> claimBatch(String workerId, int maxJobs);

    /** {@link #claimBatch} with explicit claim options (visibility timeout, maxAttempts). */
    List<VQueueJob> claimBatch(String workerId, int maxJobs, VClaimOptions options);

    /**
     * Acknowledges successful processing. Transitions the job to DONE.
     * Returns false if the job was not found or not claimed by {@code workerId}.
     */
    boolean ack(String jobId, String workerId);

    /**
     * Acknowledges with a result payload stored for later retrieval via {@link #waitResult}.
     */
    boolean ack(String jobId, String workerId, String result);

    /**
     * Nacks a job: records the error and schedules a retry after the backoff duration.
     * Once {@code maxAttempts} is exceeded the job moves to DEAD (or to the dead-letter queue).
     */
    boolean nack(String jobId, String workerId, String error);

    // ── Query / management ────────────────────────────────────────────────────

    Optional<VQueueJob> getJob(String jobId);

    /** Cancels a PENDING job. Returns false if already claimed, done, or not found. */
    boolean cancel(String jobId);

    /**
     * Blocks the calling thread until the job transitions to DONE or the timeout elapses.
     * Returns the stored result payload, or empty on timeout or missing job.
     *
     * <p>Safe to call from a virtual thread — does not pin a carrier thread.
     */
    Optional<String> waitResult(String jobId, java.time.Duration timeout);

    /** Returns all jobs in DEAD state (exhausted retries, no DLQ configured). */
    List<VQueueJob> listDeadLetters();

    /** Purges all DONE and DEAD jobs older than {@code olderThan}. Returns purge count. */
    int purge(java.time.Duration olderThan);

    // ── Functional interface ──────────────────────────────────────────────────

    @FunctionalInterface
    interface JobConsumer {
        /**
         * Process one job. Throw any exception to trigger a nack and retry.
         * To ack with a result, call {@code queue.ack(job.id(), workerId, result)} explicitly
         * instead of using {@link #consume} — or use {@link #claimBatch} directly.
         */
        void accept(VQueueJob job) throws Exception;
    }
}
