package dev.vatn.plugins.containers;

import dev.vatn.api.security.VTrustLevel;
import java.util.List;

public interface ContainerManager {
    VContainerEngine getEngineType();
    
    List<VContainer> listContainers();
    
    void startContainer(String id);
    
    void stopContainer(String id);
    
    /**
     * Executes a command within the container context, returning a process handle for interactive usage.
     */
    dev.vatn.api.VProcessService.VProcessHandle executeInteractive(String id, List<String> command, VTrustLevel trustLevel) throws java.io.IOException;
}
