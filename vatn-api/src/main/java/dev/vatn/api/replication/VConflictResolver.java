package dev.vatn.api.replication;

import dev.vatn.api.VatnApi;

/**
 * Resolves a conflict between the locally-held change for a key and an incoming change for the
 * same key during replication. The returned change is the winner that will be materialised.
 *
 * <p>Implementations must be deterministic and commutative so that all nodes converge to the same
 * value regardless of the order in which they observe changes.
 */
@VatnApi(since = "1.2")
@FunctionalInterface
public interface VConflictResolver {

    /**
     * @param local    the change currently materialised for the key (never null when called)
     * @param incoming the inbound change for the same key
     * @return the winning change
     */
    VChange resolve(VChange local, VChange incoming);

    /**
     * Last-writer-wins: higher {@link VChange#version()} wins; ties broken by later
     * {@link VChange#timestamp()}, then by greater {@link VChange#originNodeId()} (lexicographic) to
     * guarantee determinism. This is the default strategy.
     */
    static VConflictResolver lastWriteWins() {
        return (local, incoming) -> {
            if (incoming.version() != local.version()) {
                return incoming.version() > local.version() ? incoming : local;
            }
            int t = incoming.timestamp().compareTo(local.timestamp());
            if (t != 0) return t > 0 ? incoming : local;
            return incoming.originNodeId().compareTo(local.originNodeId()) > 0 ? incoming : local;
        };
    }
}
