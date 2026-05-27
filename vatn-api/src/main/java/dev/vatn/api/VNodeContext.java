package dev.vatn.api;

/**
 * Provides access to the system services of the Virtual Application Transaction Node.
 */
@VatnApi(since = "1.0")
public interface VNodeContext {
    
    /**
     * Access to the node's messaging system.
     */
    VMessaging getMessaging();
    
    /**
     * Access to the node's streaming and piping service.
     */
    VStream getStream();

    /**
     * Access to the local plugin registry.
     */
    VPluginRegistry getPluginRegistry();

    /**
     * Access to the memory channel architecture (FFI/Panama backed).
     */
    VMemoryChannel getMemory();
    
    /**
     * Access to the node's JSON serialization and query service.
     */
    VJson getJson();

    /**
     * Access to the node's configuration and trust settings.
     */
    VConfiguration getConfiguration();

    /**
     * Access to the persistent clock and scheduling service.
     */
    VClockService getClock();

    /**
     * Access to the security guard and output corridor service.
     */
    VGuardService getGuard();

    /**
     * Access to the secure secret management service.
     */
    dev.vatn.api.security.VSecretService getSecrets();

    /**
     * Access to the federated node discovery and resolution service.
     */
    VDiscovery getDiscovery();

    /**
     * Retrieves a system service by its type.
     * This is the primary extension point for future node capabilities.
     * 
     * @param serviceType The class or interface of the requested service.
     * @param <T> The service type.
     * @return An Optional containing the service if registered, or empty otherwise.
     */
     <T extends VService> java.util.Optional<T> getService(Class<T> serviceType);
    
    /**
     * Registers a custom system service implementation.
     * This allows extension layers (like a Watchdog or a specialized UI) to 
     * participate in the node's lifecycle.
     */
    <T extends VService> void registerService(Class<T> serviceType, T implementation);

    /**
     * Registers a VHttpService at the given path prefix.
     * The runtime adapter (Helidon in vatn-core) wraps the service transparently.
     * No runtime-specific types are required by the caller.
     *
     * @param path    URL prefix, e.g. {@code "/api/memory"}.
     * @param service A VHttpService implementation.
     */
    void register(String path, VHttpService service);

    /**
     * Registers an HTTP filter that intercepts every request passing through
     * plugin-registered routes. Filters run in ascending {@link VHttpFilter#order()}.
     *
     * <p>Call this from {@link VNodePlugin#onInitialize} to wire in cross-cutting
     * concerns such as security headers, authentication, rate limiting, or logging.
     *
     * @param filter the filter to add; must be thread-safe
     */
    default void registerFilter(VHttpFilter filter) {
        // default no-op; VNodeContextImpl overrides
    }

    /**
     * Identity of the current node runner.
     */
    String getNodeId();

    /**
     * Root path of the node's workspace.
     */
    java.nio.file.Path getWorkspacePath();
}
