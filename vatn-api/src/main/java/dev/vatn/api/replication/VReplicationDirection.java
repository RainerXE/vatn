package dev.vatn.api.replication;

import dev.vatn.api.VatnApi;

/**
 * Direction of replication for a set relative to this node.
 */
@VatnApi(since = "1.2")
public enum VReplicationDirection {
    /** This node pulls changes from peers; it does not push its own. */
    PULL,
    /** This node pushes its changes to peers; it does not pull theirs. */
    PUSH,
    /** This node both pulls peer changes and pushes its own (full anti-entropy). */
    BIDIRECTIONAL
}
