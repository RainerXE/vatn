package dev.vatn.core;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VatnSecurity;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.websocket.WsRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Standardized bootstrap for a VATN (Virtual Application Transpute Network).
 * Built on Helidon 4 SE and optimized for JIT/AOT versatility.
 */
public class VNodeRunner {
    private static final Logger logger = LoggerFactory.getLogger(VNodeRunner.class);
    
    private final int port;
    private final java.nio.file.Path identityPath;
    private final List<VNodePlugin> hostedPlugins = new ArrayList<>();
    private final List<io.helidon.webserver.http.HttpFeature> customFeatures = new ArrayList<>();
    private final List<ServiceRegistration> customServices = new ArrayList<>();  // kept for VNodeRunner.register() callers
    private final List<SystemServiceRegistration<?>> customSystemServices = new ArrayList<>();
    private final List<WsRouting.Builder> webSocketRoutings = new ArrayList<>();
    private final List<dev.vatn.api.VSchemaContributor> schemaContributors = new ArrayList<>();
    private final dev.vatn.core.security.VFirewallImpl firewall = new dev.vatn.core.security.VFirewallImpl();
    private final VRegistry registry;
    private dev.vatn.core.VNodeContextImpl context;
    private dev.vatn.api.VNodeIdentity identity;
    private dev.vatn.api.VMessaging messagingOverride;
    private WebServer server;
    private UdpDiscoveryTransport udpDiscovery;
    private final long startTime = System.currentTimeMillis();
    private volatile boolean started = false;

    public void setMessagingOverride(dev.vatn.api.VMessaging messaging) {
        this.messagingOverride = messaging;
    }

    private VNodeRunner(int port, java.nio.file.Path pluginPath, java.nio.file.Path identityPath) {
        this.port = port;
        this.identityPath = identityPath != null ? identityPath : java.nio.file.Paths.get(System.getProperty("user.home"), ".vatn", "identity.pem");
        
        // Initialize Core Components
        VJsonImpl json = new VJsonImpl();
        try {
            VNodeIdentityImpl localIdentity = new VNodeIdentityImpl(this.identityPath);
            dev.vatn.core.security.VPackageVerifier verifier = new dev.vatn.core.security.VPackageVerifier();
            java.nio.file.Path packageCache = java.nio.file.Paths.get(System.getProperty("user.home"), ".vatn", "packages");
            dev.vatn.core.VPackageService packageService = new dev.vatn.core.VPackageService(json, verifier, packageCache);
            
            this.registry = new VRegistry(pluginPath, packageService);
            this.identity = localIdentity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Node Identity", e);
        }
    }

    public static VNodeRunner create(int port) {
        return new VNodeRunner(port, java.nio.file.Paths.get("plugins"), null);
    }

    public static VNodeRunner create(int port, java.nio.file.Path pluginPath) {
        return new VNodeRunner(port, pluginPath, null);
    }

    public static VNodeRunner create(int port, java.nio.file.Path pluginPath, java.nio.file.Path identityPath) {
        return new VNodeRunner(port, pluginPath, identityPath);
    }

    /**
     * Starts a standalone VATN node with default settings.
     * Primary entry point for the Native Bridge.
     */
    public static VNodeContext startStandalone() {
        VNodeRunner runner = VNodeRunner.create(0); // Random port
        runner.start();
        return runner.getContext();
    }

    public dev.vatn.api.VNodeContext getContext() {
        return context;
    }

    public VRegistry getRegistry() {
        return registry;
    }

    /**
     * Adds a plugin to be hosted by this node.
     */
    public void addPlugin(VNodePlugin plugin) {
        hostedPlugins.add(plugin);
        if (registry != null) {
            registry.registerPlugin(plugin);
        }
    }

    /**
     * Adds a custom Helidon feature (e.g. specialized API router).
     */
    public void addFeature(io.helidon.webserver.http.HttpFeature feature) {
        customFeatures.add(feature);
    }

    /**
     * Registers a Helidon HttpService directly (for internal VATN use).
     * Plugin code should use context.register(path, VHttpService) instead.
     */
    public void register(String path, io.helidon.webserver.http.HttpService service) {
        customServices.add(new ServiceRegistration(path, service));
    }

    /**
     * Registers a VHttpService at a specific path (transport-neutral entry point).
     */
    public void register(String path, dev.vatn.api.VHttpService service) {
        customServices.add(new ServiceRegistration(path,
            new dev.vatn.core.transport.HelidonVHttpServiceAdapter(service)));
    }

    /**
     * Registers a custom system service before start.
     */
    public <T extends dev.vatn.api.VService> void registerService(Class<T> type, T implementation) {
        customSystemServices.add(new SystemServiceRegistration<>(type, implementation));
    }

    /**
     * Adds a custom WebSocket routing.
     */
    public void addWebSocket(WsRouting.Builder builder) {
        webSocketRoutings.add(builder);
    }

    /**
     * Registers a transport-neutral WebSocket listener at the given path.
     * Preferred over addWebSocket() — plugins should not depend on Helidon types.
     */
    public void registerWebSocket(String path, dev.vatn.api.VWsListener listener) {
        webSocketRoutings.add(WsRouting.builder()
            .endpoint(path, new dev.vatn.core.transport.HelidonVWsListenerAdapter(listener)));
    }
    
    /**
     * Adds a schema contributor to be executed during database initialization.
     */
    public void addSchemaContributor(dev.vatn.api.VSchemaContributor contributor) {
        schemaContributors.add(contributor);
    }

    private <T extends dev.vatn.api.VService> void registerHelper(dev.vatn.api.VNodeContext context, SystemServiceRegistration<T> reg) {
        context.registerService(reg.type, reg.implementation);
    }

    private record SystemServiceRegistration<T extends dev.vatn.api.VService>(Class<T> type, T implementation) {}
    private record ServiceRegistration(String path, io.helidon.webserver.http.HttpService service) {}

    /**
     * Starts the node and all hosted plugins.
     */
    public void start() {
        // Bridge JUL to SLF4J (DCN-102 cleanup)
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        
        logger.info("Starting VATN Node on port: {}", port);
        
        // 1. Initialize Registry and Configuration
        registry.start();
        VConfigurationImpl configuration = new VConfigurationImpl();
        this.context = new VNodeContextImpl(
            identity.getNodeId(), 
            firewall,
            configuration,
            registry
        );
        context.registerService(dev.vatn.api.VNodeIdentity.class, identity);
        
        // Register VUiService (D-89)
        context.registerService(dev.vatn.api.VUiService.class, new VBusUiService(context));
        
        // Register early custom services
        for (SystemServiceRegistration<?> reg : customSystemServices) {
            registerHelper(context, reg);
        }
        
        // 1.1 Initialize Persistence Service
        String jdbcUrl = "jdbc:sqlite:" + java.nio.file.Paths.get(System.getProperty("user.home"), ".vatn", "database.db");
        dev.vatn.core.memory.DatabaseManager dbManager = new dev.vatn.core.memory.DatabaseManager(jdbcUrl);
        for (dev.vatn.api.VSchemaContributor contributor : schemaContributors) {
            dbManager.registerSchemaContributor(contributor);
        }
        dbManager.registerSchemaContributor(new dev.vatn.core.workflow.VatnWorkflowSchemaContributor());
        context.registerService(dev.vatn.api.VPersistenceService.class, dbManager);
        context.registerService(dev.vatn.api.VClockService.class, new VClockServiceImpl(context, dbManager));
        context.registerService(dev.vatn.api.VFileService.class, new LocalFileService());
        context.registerService(dev.vatn.api.VProcessService.class, new LocalProcessService());
        context.registerService(dev.vatn.api.VResourceLockService.class, new dev.vatn.core.VResourceLockServiceImpl(dbManager));
        context.registerService(dev.vatn.api.VHttpClient.class, new JavaVHttpClientImpl());
        context.registerService(dev.vatn.api.VGuardService.class, new dev.vatn.core.security.VGuardServiceImpl());
        context.registerService(dev.vatn.api.VRateLimiter.class, new VTokenBucketRateLimiter());
        context.registerService(dev.vatn.api.security.VSecretService.class,
            new dev.vatn.core.security.VSecretServiceImpl(dbManager));
        context.registerService(dev.vatn.api.workflow.VEventLog.class,
            new dev.vatn.core.workflow.VEventLogImpl(dbManager));
        context.registerService(dev.vatn.api.workflow.VJobQueue.class,
            new dev.vatn.core.workflow.VJobQueueImpl(context, dbManager));

        // 1.2 Workflow Engine (DAG execution stack)
        dev.vatn.core.workflow.VDagRegistryImpl dagRegistry = new dev.vatn.core.workflow.VDagRegistryImpl();
        dev.vatn.core.workflow.VSubscriptionImpl dagSubscription = new dev.vatn.core.workflow.VSubscriptionImpl(dagRegistry);
        dev.vatn.core.workflow.VPoolManagerImpl poolManager = new dev.vatn.core.workflow.VPoolManagerImpl(dbManager);
        dev.vatn.core.workflow.VDagEngineImpl dagEngine = new dev.vatn.core.workflow.VDagEngineImpl(context, dagRegistry, poolManager, dagSubscription);
        dev.vatn.core.workflow.VDagSchedulerImpl dagScheduler = new dev.vatn.core.workflow.VDagSchedulerImpl(dagRegistry, dagEngine, dbManager);
        context.registerService(dev.vatn.api.workflow.VDagRegistry.class, dagRegistry);
        context.registerService(dev.vatn.api.workflow.VSubscription.class, dagSubscription);
        context.registerService(dev.vatn.api.workflow.VDagEngine.class, dagEngine);
        context.registerService(dev.vatn.api.workflow.VDagScheduler.class, dagScheduler);
        dagScheduler.start();

        // 1.3 Tracing — OTLP when VATN_OTLP_ENDPOINT is set, noop otherwise
        String otlpEndpoint = System.getenv("VATN_OTLP_ENDPOINT");
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            String serviceName = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "vatn-node");
            context.registerService(dev.vatn.api.VTracingService.class, new VHelidonTracingService(otlpEndpoint, serviceName));
        } else {
            context.registerService(dev.vatn.api.VTracingService.class, dev.vatn.api.VTracingService.noop());
        }

        // Apply overrides
        if (messagingOverride != null) {
            this.context.registerService(dev.vatn.api.VMessaging.class, messagingOverride);
        }
        
        for (VNodePlugin plugin : hostedPlugins) {
            logger.info("Initializing plugin: {} ({})", plugin.getName(), plugin.getId());
            ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, plugin.getId())
                .run(() -> plugin.onInitialize(context));
        }

        // 1.5 Collect HTTP services registered by plugins via context.register()
        java.util.List<dev.vatn.api.VHttpFilter> httpFilters = context.getFilters();
        for (var reg : context.getHttpRegistrations()) {
            customServices.add(new ServiceRegistration(reg.path(),
                new dev.vatn.core.transport.HelidonVHttpServiceAdapter(reg.service(), httpFilters)));
        }

        // 1.6 Collect WebSocket registrations from plugins
        for (var ws : context.getWsRegistrations()) {
            webSocketRoutings.add(WsRouting.builder()
                .endpoint(ws.path(), new dev.vatn.core.transport.HelidonVWsListenerAdapter(ws.listener())));
        }

        // 2. Start Helidon WebServer
        HttpRouting.Builder routing = HttpRouting.builder()
                .get("/health", (req, res) -> {
                    java.util.Map<String, Supplier<Boolean>> checks = context.getHealthChecks();
                    if (checks.isEmpty()) { res.send("UP"); return; }
                    StringBuilder sb = new StringBuilder("{\"status\":");
                    boolean allUp = true;
                    java.util.Map<String, String> results = new java.util.LinkedHashMap<>();
                    for (var entry : checks.entrySet()) {
                        boolean up;
                        try { up = Boolean.TRUE.equals(entry.getValue().get()); }
                        catch (Exception e) { up = false; }
                        results.put(entry.getKey(), up ? "UP" : "DOWN");
                        if (!up) allUp = false;
                    }
                    sb.append(allUp ? "\"UP\"" : "\"DOWN\"").append(",\"checks\":{");
                    results.forEach((k, v) -> sb.append("\"").append(k).append("\":\"").append(v).append("\","));
                    if (!results.isEmpty()) sb.setLength(sb.length() - 1);
                    sb.append("}}");
                    res.headers().contentType(io.helidon.http.HttpMediaTypes.JSON_UTF_8);
                    if (!allUp) res.status(io.helidon.http.Status.SERVICE_UNAVAILABLE_503);
                    res.send(sb.toString());
                })
                .get("/vatn/health", (req, res) -> {
                    res.headers().contentType(io.helidon.http.HttpMediaTypes.JSON_UTF_8);
                    res.send("{\"status\":\"UP\",\"nodeId\":\"" + context.getNodeId()
                        + "\",\"uptimeMs\":" + (System.currentTimeMillis() - startTime) + "}");
                })
                .get("/vatn/ready", (req, res) -> {
                    res.headers().contentType(io.helidon.http.HttpMediaTypes.JSON_UTF_8);
                    if (started) {
                        res.send("{\"status\":\"READY\"}");
                    } else {
                        res.status(io.helidon.http.Status.SERVICE_UNAVAILABLE_503)
                           .send("{\"status\":\"STARTING\"}");
                    }
                })
                .get("/info", (req, res) -> {
                    java.util.Map<String, Object> info = java.util.Map.of(
                        "id", context.getNodeId(),
                        "flavor", context.getConfiguration().isAot() ? "AOT" : "JVM",
                        "vatnVersion", "1.0.0",
                        "plugins", hostedPlugins.size(),
                        "uptimeMs", System.currentTimeMillis() - startTime
                    );
                    res.send(context.getJson().stringify(info));
                })
                .put("/stream/{id}", (req, res) -> {
                    String id = req.path().pathParameters().get("id");
                    logger.info("Ingesting stream: {}", id);
                    
                    try {
                        PipedOutputStream pos = new PipedOutputStream();
                        PipedInputStream pis = new PipedInputStream(pos);
                        
                        // Register the consumer-end immediately so the plugin can find it
                        ((VStreamServiceImpl) context.getStream()).ingest(id, pis);
                        
                        // Transfer data from the network to the local pipe.
                        // Since Helidon uses Virtual Threads per request, this blocking transfer is efficient.
                        try (InputStream networkIn = req.content().inputStream(); pos) {
                            networkIn.transferTo(pos);
                        }
                        
                        logger.info("Completed ingestion for stream: {}", id);
                        res.send("COMPLETED");
                    } catch (IOException e) {
                        logger.error("Stream ingestion failure for {}", id, e);
                        res.status(io.helidon.http.Status.INTERNAL_SERVER_ERROR_500).send(e.getMessage());
                    }
                });

        // SSE bridge: GET /vatn/ui/stream — streams VBusUiService events to clients
        routing.get("/vatn/ui/stream", (req, res) -> {
            VBusUiService uiService = context.getService(dev.vatn.api.VUiService.class)
                .filter(s -> s instanceof VBusUiService)
                .map(s -> (VBusUiService) s)
                .orElse(null);
            if (uiService == null) {
                res.status(io.helidon.http.Status.SERVICE_UNAVAILABLE_503).send();
                return;
            }
            java.util.concurrent.LinkedBlockingQueue<String> q = uiService.openEventStream();
            try (io.helidon.webserver.sse.SseSink sink = res.sink(io.helidon.webserver.sse.SseSink.TYPE)) {
                while (!Thread.currentThread().isInterrupted()) {
                    String json = q.poll(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (json == null) {
                        // Keepalive — prevents proxies from closing idle connections
                        sink.emit(io.helidon.http.sse.SseEvent.builder()
                            .name("ping").data("").build());
                    } else {
                        sink.emit(io.helidon.http.sse.SseEvent.builder()
                            .name("ui-update").data(json).build());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Client disconnected — normal exit from the loop
            } finally {
                uiService.closeEventStream(q);
            }
        });

        // Register Application Features
        for (io.helidon.webserver.http.HttpFeature feature : customFeatures) {
            routing.update(feature::setup);
        }

        // Register Application Services
        for (ServiceRegistration reg : customServices) {
            routing.register(reg.path, reg.service);
        }
        
        var webServerBuilder = WebServer.builder()
                .port(port)
                .routing(routing);

        for (WsRouting.Builder ws : webSocketRoutings) {
            webServerBuilder.addRouting(ws);
        }

        server = webServerBuilder.build();

        server.start();
        
        // Ensure the server is actually running before proceeding
        if (!server.isRunning()) {
            throw new RuntimeException("Helidon server failed to start on port " + port);
        }

        // 3. Post-Startup Lattice Federation
        VJsonImpl json = new VJsonImpl();
        VDiscoveryImpl discovery = new VDiscoveryImpl(context, json, identity);
        VNameResolverImpl nameResolver = new VNameResolverImpl(discovery);
        
        context.registerService(dev.vatn.api.VDiscovery.class, discovery);
        context.registerService(dev.vatn.api.VNameResolver.class, nameResolver);
        
        // Start LAN discovery
        udpDiscovery = new UdpDiscoveryTransport(discovery);
        udpDiscovery.start();
        
        String myUri = "http://localhost:" + server.port(); 
        discovery.announce(context.getNodeId(), myUri);
        
        logger.info("VATN Node Ready on port {}. Lattice Federation Active.", server.port());
        this.started = true;

        // Notify plugins that the server is fully bound and all siblings are initialized
        hostedPlugins.forEach(plugin ->
                Thread.ofVirtual().start(() -> {
                    try { plugin.onReady(); }
                    catch (Exception e) { logger.warn("onReady() failed for {}: {}", plugin.getId(), e.getMessage()); }
                }));
    }

    public int getBoundPort() {
        if (server == null || !server.isRunning()) {
            return port;
        }
        return server.port();
    }

    public void stop() {
        if (udpDiscovery != null) {
            udpDiscovery.stop();
        }
        if (server != null) {
            server.stop();
        }
        if (context != null) {
            context.getService(dev.vatn.api.workflow.VDagScheduler.class)
                .ifPresent(dev.vatn.api.workflow.VDagScheduler::stop);
            for (Class<? extends dev.vatn.api.VService> svcType : java.util.List.of(
                    dev.vatn.api.VClockService.class,
                    dev.vatn.api.workflow.VJobQueue.class)) {
                context.getService(svcType).ifPresent(svc -> {
                    if (svc instanceof AutoCloseable) {
                        try { ((AutoCloseable) svc).close(); }
                        catch (Exception e) { logger.warn("Failed to close service {}", svcType.getSimpleName(), e); }
                    }
                });
            }
        }
        for (VNodePlugin plugin : hostedPlugins) {
            ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, plugin.getId())
                .run(() -> plugin.onShutdown());
        }
    }
}
