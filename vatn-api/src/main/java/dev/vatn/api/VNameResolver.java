package dev.vatn.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Pseudo-DNS for the VATN lattice.
 * Resolves logical vatn:// URIs to physical socket addresses.
 */
@VatnApi(since = "1.0")
public interface VNameResolver extends VService {

    /**
     * Resolves a logical URI to a physical endpoint.
     * Example: vatn://my-plugin/memory -> http://192.168.1.10:8080/memory
     */
    CompletableFuture<URI> resolve(String vatnUri);

    /**
     * Resolves a logical URI to all available physical endpoints across the lattice.
     */
    CompletableFuture<List<URI>> resolveAll(String vatnUri);

    /**
     * Returns a map of all services currently discovered in the lattice.
     * Key: Service ID, Value: List of hosting Node IDs.
     */
    Map<String, List<String>> getLatticeCatalog();
}
