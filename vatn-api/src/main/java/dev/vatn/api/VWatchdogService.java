package dev.vatn.api;

/**
 * VWatchdogService is the central daemon supervisor for the VATN lattice node.
 * It is responsible for physically spawning configured process execution targets
 * (out-of-process plugins), monitoring their IPC heartbeats, and automatically
 * restarting those that crash or disconnect unexpectedly.
 */
@VatnApi(since = "1.0")
public interface VWatchdogService extends VService {

    /**
     * Registers a plugin descriptor to be supervised by the Watchdog.
     * The Watchdog will evaluate its execution mode and if it is an
     * OUT_OF_PROCESS_BIN, it will spawn the subprocess and open IPC sockets.
     * 
     * @param pluginId the plugin to supervise.
     */
    void supervise(String pluginId, VPluginDescriptor descriptor);

    /**
     * Stops supervising a plugin and gracefully terminates its subprocess.
     */
    void drop(String pluginId);

    /**
     * Fully starts the watchdog daemon polling loop.
     */
    void start();

    /**
     * Stops the watchdog and cleanly kills all supervised child branches.
     */
    void stop();
    
    /**
     * Polls the health of a supervised plugin dynamically.
     * @return true if the process is alive and heartbeating.
     */
    boolean isHealthy(String pluginId);
}
