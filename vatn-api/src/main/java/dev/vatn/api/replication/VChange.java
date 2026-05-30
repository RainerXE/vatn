package dev.vatn.api.replication;

import dev.vatn.api.VatnApi;

import java.time.Instant;

/**
 * A single change to a replicated key — the unit of the change feed and of conflict resolution.
 *
 * @param set          the replicated set this change belongs to
 * @param key          the logical key
 * @param value        the new value bytes, or {@code null} when {@code tombstone} is true
 * @param version      a monotonic logical version (Lamport clock) for the set; higher is newer
 * @param originNodeId the node that originally authored the change
 * @param timestamp    wall-clock authoring time (used as the last-writer-wins tiebreak)
 * @param tombstone    true if this change is a deletion
 */
@VatnApi(since = "1.2")
public record VChange(
        String  set,
        String  key,
        byte[]  value,
        long    version,
        String  originNodeId,
        Instant timestamp,
        boolean tombstone
) {}
