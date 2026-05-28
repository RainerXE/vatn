package dev.vatn.core;

import dev.vatn.api.*;
import dev.vatn.api.security.VFirewall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


/**
 * Platform implementation of the VNodeContext.
 * Acts as a dynamic registry for system services (Messaging, Memory, Config).
 */
public class VNodeContextImpl implements VNodeContext {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(VNodeContextImpl.class);
    private final String nodeId;
    private final VRegistry registry;
    private final VStreamServiceImpl streamService;
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private final List<HttpRegistration> httpRegistrations = new ArrayList<>();
    private final List<dev.vatn.api.VHttpFilter> httpFilters = new ArrayList<>();
    private final List<WsRegistration> wsRegistrations = new ArrayList<>();
    private final Map<String, Supplier<Boolean>> healthChecks = new LinkedHashMap<>();
    private final List<AgentRegistration> agentRegistrations = new ArrayList<>();

    public record HttpRegistration(String path, VHttpService service) {}
    public record WsRegistration(String path, dev.vatn.api.VWsListener listener) {}
    public record AgentRegistration(dev.vatn.api.VAgent agent, dev.vatn.api.VAgentMode mode) {}

    public VNodeContextImpl(String nodeId, VFirewall firewall, VConfiguration configuration, VRegistry registry) {
        this.nodeId = nodeId;
        this.registry = registry;
        VMessaging messaging = new dev.vatn.core.transport.OipcMessagingTransport();
        this.streamService = new VStreamServiceImpl(registry, messaging);

        // Register default services
        registerService(VConfiguration.class, configuration);
        registerService(VMessaging.class, messaging);
        registerService(VJson.class, new dev.vatn.core.VJsonServiceImpl());
        registerService(VStream.class, streamService);
        registerService(VPluginRegistry.class, registry);
    }

    public VNodeContextImpl(String nodeId, VFirewall firewall, Map<Class<?>, Object> initialServices) {
        this.nodeId = nodeId;
        this.registry = null;
        this.streamService = null;
        this.services.putAll(initialServices);
    }

    /**
     * Registers a service implementation for a specific type.
     */
    @Override
    public final <T extends VService> void registerService(Class<T> serviceType, T implementation) {
        services.put(serviceType, implementation);
    }

    @Override
    public VMessaging getMessaging() {
        return getService(VMessaging.class).orElse(null);
    }

    @Override
    public VStream getStream() {
        return streamService != null ? streamService : getService(VStream.class).orElse(null);
    }

    @Override
    public VPluginRegistry getPluginRegistry() {
        return registry;
    }

    @Override
    public VMemoryChannel getMemory() {
        return getService(VMemoryChannel.class).orElse(null);
    }

    @Override
    public VJson getJson() {
        return getService(VJson.class).orElse(null);
    }

    @Override
    public VConfiguration getConfiguration() {
        return getService(VConfiguration.class).orElse(null);
    }

    @Override
    public VClockService getClock() {
        return getService(VClockService.class).orElse(null);
    }

    @Override
    public VGuardService getGuard() {
        return getService(VGuardService.class).orElse(null);
    }

    @Override
    public dev.vatn.api.security.VSecretService getSecrets() {
        return getService(dev.vatn.api.security.VSecretService.class).orElse(null);
    }

    @Override
    public VDiscovery getDiscovery() {
        return getService(VDiscovery.class).orElse(null);
    }

    @Override
    public <T extends VService> Optional<T> getService(Class<T> serviceType) {
        Object service = services.get(serviceType);
        if (serviceType.isInstance(service)) {
            return Optional.of(serviceType.cast(service));
        }
        return Optional.empty();
    }

    @Override
    public void register(String path, VHttpService service) {
        httpRegistrations.add(new HttpRegistration(path, service));
        logger.debug("[VATN] Queued HTTP service: {} -> {}", path, service.getClass().getSimpleName());
    }

    /** Returns all registered HTTP services. Called by VNodeRunner after plugin init. */
    public List<HttpRegistration> getHttpRegistrations() {
        return Collections.unmodifiableList(httpRegistrations);
    }

    @Override
    public void registerFilter(dev.vatn.api.VHttpFilter filter) {
        httpFilters.add(filter);
        logger.debug("[VATN] Registered HTTP filter: {} (order={})",
                filter.getClass().getSimpleName(), filter.order());
    }

    /** Returns all registered filters sorted by order(). Called by VNodeRunner. */
    public List<dev.vatn.api.VHttpFilter> getFilters() {
        return httpFilters.stream()
                .sorted(java.util.Comparator.comparingInt(dev.vatn.api.VHttpFilter::order))
                .toList();
    }

    @Override
    public void registerWebSocket(String path, dev.vatn.api.VWsListener listener) {
        wsRegistrations.add(new WsRegistration(path, listener));
        logger.debug("[VATN] Queued WebSocket endpoint: {}", path);
    }

    public List<WsRegistration> getWsRegistrations() {
        return Collections.unmodifiableList(wsRegistrations);
    }

    @Override
    public void registerHealthCheck(String name, Supplier<Boolean> checker) {
        healthChecks.put(name, checker);
        logger.debug("[VATN] Registered health check: {}", name);
    }

    public Map<String, Supplier<Boolean>> getHealthChecks() {
        return Collections.unmodifiableMap(healthChecks);
    }

    @Override
    public void registerAgent(dev.vatn.api.VAgent agent, dev.vatn.api.VAgentMode mode) {
        agentRegistrations.add(new AgentRegistration(agent, mode));
        logger.debug("[VATN] Registered agent: {} (strategy={})", agent.getId(), mode.strategy());
    }

    public List<AgentRegistration> getAgentRegistrations() {
        return Collections.unmodifiableList(agentRegistrations);
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public java.nio.file.Path getWorkspacePath() {
        String envPath = System.getenv("VATN_WORKSPACE");
        if (envPath != null && !envPath.isBlank()) {
            return java.nio.file.Paths.get(envPath);
        }
        String sysProp = System.getProperty("vatn.workspace");
        if (sysProp != null && !sysProp.isBlank()) {
            return java.nio.file.Paths.get(sysProp);
        }
        return java.nio.file.Paths.get(System.getProperty("user.dir"));
    }
}
