package dev.vatn.api.replication;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.util.Collection;
import java.util.Optional;

/**
 * Index sync / replication primitive: a change-feed-driven, peer-to-peer key/value replication
 * layer with per-peer watermarks, pluggable conflict resolution, and partial replication.
 *
 * <p>Built on the node's existing primitives — the durable change feed
 * ({@link dev.vatn.api.VTopic}), cross-node {@link dev.vatn.api.VRpcService}, and
 * {@link dev.vatn.api.VDiscovery} peer enumeration. Each replicated set converges across all
 * participating nodes by repeatedly transferring only the delta past each peer's watermark and
 * resolving conflicts deterministically.
 *
 * <pre>{@code
 * VReplicationService repl = ctx.getService(VReplicationService.class).orElseThrow();
 *
 * VReplicatedSet library = repl.replicate(
 *     VReplicationConfig.of("library")
 *         .withDirection(VReplicationDirection.BIDIRECTIONAL)
 *         .withFilter((c, peer) -> c.key().startsWith("public/"))); // partial replication
 *
 * library.put("public/book/42", jsonBytes);   // replicates to peers
 * library.get("public/book/42");               // local read
 * }</pre>
 */
@VatnApi(since = "1.2")
public interface VReplicationService extends VService {

    /**
     * Begins replicating a set with the given configuration, or returns the existing handle if a
     * set with that name is already active.
     */
    VReplicatedSet replicate(VReplicationConfig config);

    /** Returns the active set with the given name, if any. */
    Optional<VReplicatedSet> get(String name);

    /** All currently active replicated sets. */
    Collection<VReplicatedSet> active();

    /** Stops replicating the named set and releases its background sync. */
    void stop(String name);
}
