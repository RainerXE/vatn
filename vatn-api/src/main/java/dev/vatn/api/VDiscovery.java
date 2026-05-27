package dev.vatn.api;

import java.util.Optional;

/**
 * Service for federated node discovery and resolution.
 * Allows nodes to announce their presence and resolve other node IDs within the federation.
 */
@VatnApi(since = "1.0")
public interface VDiscovery extends VService {

    /**
     * Announces this node's presence to the federation.
     * @param nodeId The local node ID.
     * @param endpointUri The URI where this node can be reached (e.g. "http://192.168.1.10:8080").
     */
    void announce(String nodeId, String endpointUri);

    /**
     * Resolves a remote node ID to its reachability URI.
     * @param nodeId The target node ID.
     * @return An Optional containing the URI if found, or empty.
     */
    Optional<String> resolve(String nodeId);

    /**
     * Explicitly registers a remote node (Seed Node).
     * @param nodeId The remote node ID.
     * @param endpointUri The remote endpoint.
     */
    void registerRemote(String nodeId, String endpointUri);

    /**
     * Requests all peers to re-announce themselves.
     */
    void requestSync();
}
