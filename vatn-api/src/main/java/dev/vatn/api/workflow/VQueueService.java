package dev.vatn.api.workflow;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

/**
 * Entry point for VATN's named work queues.
 *
 * <p>A named queue is an at-least-once, durable, priority-aware work queue backed by the node's
 * SQLite database. Any number of named queues can coexist in the same DB file, each with
 * independent workers, retry policies, and dead-letter targets.
 *
 * <p>Unlike {@link VJobQueue} — which is a single global queue with registered handler callbacks —
 * {@code VQueueService} exposes named queues with explicit claim/ack semantics, so callers control
 * when a job is acknowledged and can implement batching, result storage, and custom retry logic.
 *
 * <pre>{@code
 * VNamedQueue emails = ctx.getService(VQueueService.class).orElseThrow().queue("emails");
 *
 * // Producer
 * String jobId = emails.enqueue("{\"to\":\"alice@example.com\"}");
 *
 * // Consumer (runs in a background virtual thread, restarts automatically)
 * emails.consume("worker-1", job -> {
 *     send(job.payload());
 *     job.ack();
 * });
 * }</pre>
 *
 * <p><b>Atomic enqueue with a business write:</b>
 * <pre>{@code
 * try (Connection conn = ctx.getService(VPersistenceService.class).orElseThrow().getConnection()) {
 *     conn.setAutoCommit(false);
 *     try (var ps = conn.prepareStatement("INSERT INTO orders(user_id) VALUES (?)")) {
 *         ps.setInt(1, userId);
 *         ps.executeUpdate();
 *     }
 *     emails.enqueue("{\"to\":\"alice@example.com\"}", conn);  // same transaction
 *     conn.commit();
 * }
 * }</pre>
 *
 * <p>Inspired by <a href="https://github.com/russellromney/honker">honker</a>'s insight that if
 * SQLite is the primary datastore, the queue should live in the same file — eliminating the
 * dual-write problem between business tables and a separate broker.
 *
 * @see VNamedQueue
 * @see VJobQueue
 */
@VatnApi(since = "1.0-alpha.9")
public interface VQueueService extends VService {

    /**
     * Returns a named queue handle. The queue is created lazily on first enqueue.
     * Multiple calls with the same name return handles backed by the same rows.
     */
    VNamedQueue queue(String name);

    /**
     * Returns a named queue handle with custom default claim options.
     * These options are used when {@link VNamedQueue#consume} is called without explicit options.
     */
    VNamedQueue queue(String name, VClaimOptions defaultOptions);
}
