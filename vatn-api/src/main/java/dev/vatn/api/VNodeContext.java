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
     * Registers an HTTP filter scoped to a path prefix.
     * The filter only runs for requests whose path starts with {@code pathPrefix}.
     *
     * <pre>{@code
     * // Only authenticate requests under /api
     * ctx.registerFilter(new AuthFilter(authService), "/api");
     * }</pre>
     */
    default void registerFilter(VHttpFilter filter, String pathPrefix) {
        registerFilter(new VHttpFilter() {
            @Override public int order() { return filter.order(); }
            @Override
            public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
                if (req.getPath().startsWith(pathPrefix)) {
                    filter.doFilter(req, res, chain);
                } else {
                    chain.proceed(req, res);
                }
            }
        });
    }

    /**
     * Registers a transport-neutral WebSocket endpoint at the given path.
     * Preferred over using Helidon types directly — plugins must not depend on
     * runtime-specific classes.
     *
     * <pre>{@code
     * ctx.registerWebSocket("/ws/chat", new ChatWsListener());
     * }</pre>
     */
    default void registerWebSocket(String path, VWsListener listener) {
        // default no-op; VNodeContextImpl overrides
    }

    /**
     * Registers a named health check. All registered checks are aggregated into
     * the {@code GET /health} and {@code GET /vatn/health} endpoints.
     *
     * <pre>{@code
     * ctx.registerHealthCheck("redis", () -> redisService.ping());
     * ctx.registerHealthCheck("db",    () -> dataSource.getConnection() != null);
     * }</pre>
     */
    default void registerHealthCheck(String name, java.util.function.Supplier<Boolean> checker) {
        // default no-op; VNodeContextImpl overrides
    }

    /**
     * Registers an outbound agent with the default {@link VAgentMode#singleton()} strategy.
     * The agent is started after all plugins are initialized.
     *
     * <pre>{@code
     * ctx.registerAgent(new TelegramAgent(token));
     * }</pre>
     */
    default void registerAgent(VAgent agent) {
        registerAgent(agent, VAgentMode.singleton());
    }

    /**
     * Registers an outbound agent with an explicit deployment strategy.
     *
     * <pre>{@code
     * ctx.registerAgent(new RcsGateway(config),
     *         VAgentMode.activePassive().withFailoverTimeout(10_000));
     * }</pre>
     */
    default void registerAgent(VAgent agent, VAgentMode mode) {
        // default no-op; VNodeContextImpl overrides
    }

    /**
     * Returns live snapshots of all registered agents, including their current role.
     * The list updates dynamically as roles change via failover or resign.
     */
    @VatnApi(since = "1.0-alpha.7")
    default java.util.List<VAgentInfo> getAgentInfos() { return java.util.List.of(); }

    /**
     * Returns the HTTP paths registered by plugins via {@link #register}.
     * Used by tooling such as the admin UI.
     */
    @VatnApi(since = "1.0-alpha.7")
    default java.util.List<String> getRegisteredRoutes() { return java.util.List.of(); }

    /**
     * Access to the DAG workflow engine (trigger and inspect workflow runs).
     * Default implementation resolves via {@link #getService(Class)}.
     */
    @VatnApi(since = "1.0-alpha.15")
    default dev.vatn.api.workflow.VDagEngine getDagEngine() {
        return getService(dev.vatn.api.workflow.VDagEngine.class)
                .orElseThrow(() -> new IllegalStateException(
                        "VDagEngine service is not registered in this context"));
    }

    /**
     * Access to the DAG registry (register DAG definitions and operators).
     * Default implementation resolves via {@link #getService(Class)}.
     */
    @VatnApi(since = "1.0-alpha.15")
    default dev.vatn.api.workflow.VDagRegistry getDagRegistry() {
        return getService(dev.vatn.api.workflow.VDagRegistry.class)
                .orElseThrow(() -> new IllegalStateException(
                        "VDagRegistry service is not registered in this context"));
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
