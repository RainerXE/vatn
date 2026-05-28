package dev.vatn.api;

import dev.vatn.api.VatnApi;

import java.sql.Connection;
import java.util.List;

/**
 * A named, durable, append-only event topic backed by the node's SQLite database.
 *
 * <p>Every event published to a topic is persisted as a row in {@code vatn_topic_events}.
 * Each named consumer tracks its own read position in {@code vatn_topic_offsets}.
 * On restart, a consumer replays all events past its last saved offset, then transitions
 * to live delivery.
 *
 * <h3>Publish</h3>
 * <pre>{@code
 * VTopic events = ctx.getService(VTopicService.class).orElseThrow().topic("user-events");
 * events.publish("{\"userId\":42,\"action\":\"login\"}");
 * }</pre>
 *
 * <h3>Subscribe</h3>
 * <pre>{@code
 * // starts background virtual thread; replays from saved offset, then goes live
 * VTopicSubscription sub = events.subscribe("audit-log", event ->
 *     auditLog.record(event.id(), event.payload()));
 * }</pre>
 *
 * <h3>Atomic publish with a business write</h3>
 * <pre>{@code
 * Connection conn = ctx.getService(VPersistenceService.class).orElseThrow().getConnection();
 * conn.setAutoCommit(false);
 * // ...business INSERT...
 * events.publish("{\"userId\":42}", conn);   // same transaction
 * conn.commit();
 * }</pre>
 *
 * <h3>Multiple independent consumers</h3>
 * <pre>{@code
 * events.subscribe("audit-log",  e -> audit(e));     // reads at its own pace
 * events.subscribe("analytics",  e -> ingest(e));    // independent offset
 * events.subscribe("websocket",  e -> push(e));      // yet another cursor
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.9")
public interface VTopic {

    /** The topic name this handle was opened for. */
    String name();

    // ── Publish ───────────────────────────────────────────────────────────────

    /** Appends a JSON payload. Returns the assigned event ID (monotonically increasing). */
    long publish(String payload);

    /**
     * Appends a payload within an existing JDBC transaction.
     * The caller owns commit/rollback. Combine with a business write for atomic delivery.
     */
    long publish(String payload, Connection tx);

    // ── Subscribe ─────────────────────────────────────────────────────────────

    /**
     * Opens a subscription for {@code consumerId}. Starts a background virtual thread that:
     * <ol>
     *   <li>Reads the saved offset for this consumer from {@code vatn_topic_offsets}.</li>
     *   <li>Replays all events with id {@code > savedOffset}, delivering each to {@code handler}.</li>
     *   <li>Polls for new events every ~100 ms (configurable via system property
     *       {@code vatn.topic.pollIntervalMs}).</li>
     *   <li>Auto-saves the offset every 1 000 events or 1 second, whichever comes first.</li>
     * </ol>
     * Returns immediately; call {@link VTopicSubscription#close()} to stop.
     */
    VTopicSubscription subscribe(String consumerId, EventHandler handler);

    /**
     * Moves the saved offset for {@code consumerId} to {@code offset}.
     * {@code 0} replays the full topic from the beginning.
     * Takes effect on the next poll cycle.
     */
    void seek(String consumerId, long offset);

    /** Returns the saved offset for {@code consumerId}, or 0 if never subscribed. */
    long getOffset(String consumerId);

    /** Returns the id of the most recently published event, or 0 if the topic is empty. */
    long latestOffset();

    /** Returns up to {@code limit} events with id {@code > afterOffset}, ordered ascending. */
    List<VTopicEvent> read(long afterOffset, int limit);

    /**
     * Deletes events with id {@code <= beforeOffset}.
     * Consumers whose saved offset is below {@code beforeOffset} will miss those events.
     * Use for housekeeping on high-volume topics.
     */
    int prune(long beforeOffset);

    // ── Functional interface ──────────────────────────────────────────────────

    @FunctionalInterface
    interface EventHandler {
        void onEvent(VTopicEvent event);
    }
}
