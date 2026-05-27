package dev.vatn.api;

/**
 * Interface for a proxy that handles communication with an out-of-process or 
 * remote VATN plugin.
 */
@VatnApi(since = "1.0")
public interface VRemotePluginProxy extends VNodePlugin {

    /**
     * Returns the transport used to communicate with the remote plugin.
     */
    VTransport getTransport();

    /**
     * Returns the physical address or endpoint of the remote plugin.
     */
    String getAddress();

    /**
     * Checks if the remote plugin is currently reachable.
     */
    boolean isAlive();
}
