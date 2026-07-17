package dev.vatn.api.admin;

import dev.vatn.api.VatnApi;
import java.util.List;

/**
 * SPI for execution engines to report their active workloads to the global registry.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VWorkloadProvider {
    
    /**
     * @return the list of currently active workloads managed by this provider.
     */
    List<VWorkload> getActiveWorkloads();
}
