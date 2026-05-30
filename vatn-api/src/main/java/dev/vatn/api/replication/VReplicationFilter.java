package dev.vatn.api.replication;

import dev.vatn.api.VatnApi;

/**
 * Decides whether a given change should be replicated to a given peer — the hook for
 * <em>partial replication</em> (sharding, per-peer subsets, redaction by key prefix, etc.).
 *
 * <p>Evaluated on the sending side when serving a peer's pull/push: a change for which this returns
 * false is simply omitted from the batch sent to that peer.
 */
@VatnApi(since = "1.2")
@FunctionalInterface
public interface VReplicationFilter {

    /**
     * @param change     the change under consideration
     * @param peerNodeId the peer the change would be sent to
     * @return true to replicate the change to {@code peerNodeId}
     */
    boolean shouldReplicate(VChange change, String peerNodeId);

    /** Replicates everything to every peer (full replication). */
    static VReplicationFilter all() {
        return (change, peerNodeId) -> true;
    }
}
