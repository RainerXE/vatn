package dev.vatn.api.replication;

import dev.vatn.api.VatnApi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A handle to a replicated key/value set: a local materialised store plus a change feed that is
 * synchronised with peers under the configured {@link VReplicationConfig}.
 *
 * <p>Local writes go through {@link #put}/{@link #delete}; each assigns a new version, appends to
 * the change feed, and (depending on direction) propagates to peers. Inbound peer changes are
 * applied through the configured {@link VConflictResolver}. Per-peer inbound progress is tracked as
 * a {@linkplain #watermark(String) watermark} so each sync pass only transfers the delta.
 */
@VatnApi(since = "1.2")
public interface VReplicatedSet {

    /** Runtime counters for a replicated set. */
    record Stats(
            long changesSent,
            long changesReceived,
            long conflictsResolved,
            long feedHead,
            Instant lastSyncAt
    ) {}

    /** The set name. */
    String name();

    // ── local writes ─────────────────────────────────────────────────────────

    /** Records a local upsert: assigns a new version, materialises it, and appends to the feed. */
    void put(String key, byte[] value);

    /** Records a local deletion as a tombstone so the delete also replicates. */
    void delete(String key);

    // ── local reads ──────────────────────────────────────────────────────────

    /** Returns the current value for {@code key}, or empty if absent or tombstoned. */
    Optional<byte[]> get(String key);

    /** Lists current (non-tombstoned) keys beginning with {@code prefix}. */
    List<String> keys(String prefix);

    // ── replication state ──────────────────────────────────────────────────────

    /** The local change-feed head — the highest feed offset authored or applied locally. */
    long feedHead();

    /**
     * The inbound watermark for {@code peerNodeId}: the highest feed offset from that peer that has
     * been applied locally. The next pull from that peer requests changes after this offset.
     */
    long watermark(String peerNodeId);

    /**
     * Applies a batch of inbound changes from {@code fromNodeId} through the conflict resolver and
     * advances that peer's watermark to {@code throughOffset}. Returns the number of changes that
     * actually won and were materialised. Normally called by the runtime; exposed for testing and
     * custom transports.
     */
    int applyInbound(String fromNodeId, List<VChange> changes, long throughOffset);

    /**
     * Returns up to {@code limit} local feed changes with offset greater than {@code afterOffset},
     * for serving a peer's pull. The runtime applies the {@link VReplicationFilter} before sending.
     */
    List<VChange> changesSince(long afterOffset, int limit);

    /** Triggers an immediate synchronisation pass against all known peers. */
    void syncNow();

    /** Current counters. */
    Stats stats();

    /** Stops background sync for this set. */
    void close();
}
