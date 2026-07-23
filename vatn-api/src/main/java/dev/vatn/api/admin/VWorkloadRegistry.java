package dev.vatn.api.admin;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;
import java.util.List;

/**
 * A central registry for discovering all active workloads on the node.
 * Pluggable execution engines (like Containers or WASM) register their
 * {@link VWorkloadProvider} instances here to feed into the global admin view.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VWorkloadRegistry extends VService {

    /**
     * Registers a new provider. Usually called by plugins in {@code onInitialize}.
     * 
     * @param provider the workload provider
     */
    void registerProvider(VWorkloadProvider provider);

    /**
     * Polls all registered providers and aggregates the active workloads.
     * 
     * @return a combined list of all running workloads across all execution modes
     */
    List<VWorkload> getAllWorkloads();
}
