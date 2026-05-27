package dev.vatn.core.workflow;

import dev.vatn.api.*;
import dev.vatn.api.workflow.*;
import dev.vatn.core.VJsonImpl;
import dev.vatn.core.memory.DatabaseManager;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;

/**
 * Test harness that wires up the full VDagEngine stack against a temp SQLite database.
 * Registers all pre-built test DAGs before returning the engine.
 */
class VDagEngineTestHarness implements AutoCloseable {

    private final DatabaseManager db;
    private final VDagRegistryImpl registry;
    private final VPoolManagerImpl poolManager;
    private final VSubscriptionImpl subscriptions;
    private final VDagEngineImpl engine;
    private final VNodeContext nodeContext;
    private final VEventLogImpl eventLog;

    VDagEngineTestHarness(Path tempDir) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath();
        this.db = new DatabaseManager(jdbcUrl);

        // Register workflow schema
        db.registerSchemaContributor(new VatnWorkflowSchemaContributor());

        // Build registry and engine
        this.registry = new VDagRegistryImpl();
        this.subscriptions = new VSubscriptionImpl(registry);
        this.poolManager = new VPoolManagerImpl(db);
        this.eventLog = new VEventLogImpl(db);

        // Build minimal VNodeContext backed by the test DB (includes VEventLog)
        this.nodeContext = buildTestContext(db, eventLog);

        this.engine = new VDagEngineImpl(nodeContext, registry, poolManager, subscriptions);

        // Pre-register test DAGs
        registerTestDags();
    }

    VDagEngine engine() { return engine; }
    VDagRegistry registry() { return registry; }
    VNodeContext nodeContext() { return nodeContext; }
    dev.vatn.core.memory.DatabaseManager db() { return db; }
    VEventLogImpl eventLog() { return eventLog; }
    VXCom xcom(String runId) { return new VXComImpl(runId, db); }

    @Override
    public void close() {
        // VDagEngineImpl has no stop() — GC handles cleanup; DB connections close with the pool
    }

    private void registerTestDags() {
        // single-test
        registry.register(VDag.manual("single-test", "Single task",
                Map.of("t1", VDagTask.of("t1", "noop", Set.of(), Map.of()))));

        // linear-test
        Map<String, VDagTask> linear = new LinkedHashMap<>();
        linear.put("a", VDagTask.of("a", "noop", Set.of(), Map.of()));
        linear.put("b", VDagTask.of("b", "noop", Set.of("a"), Map.of()));
        registry.register(VDag.manual("linear-test", "Linear", linear));

        // fanout-test
        Map<String, VDagTask> fanout = new LinkedHashMap<>();
        fanout.put("t0", VDagTask.of("t0", "noop", Set.of(), Map.of()));
        fanout.put("t1", VDagTask.of("t1", "noop", Set.of("t0"), Map.of()));
        fanout.put("t2", VDagTask.of("t2", "noop", Set.of("t0"), Map.of()));
        fanout.put("t3", VDagTask.of("t3", "noop", Set.of("t0"), Map.of()));
        registry.register(VDag.manual("fanout-test", "Fan-out", fanout));
    }

    private static VNodeContext buildTestContext(VPersistenceService persistenceService,
                                                   VEventLogImpl eventLog) {
        VJsonImpl json = new VJsonImpl();
        VMessaging messaging = new dev.vatn.core.test.MockMessagingImpl();
        return new VNodeContext() {
            @Override public String getNodeId()           { return "test-node"; }
            @Override public java.nio.file.Path getWorkspacePath() { return Path.of("."); }
            @Override public VJson getJson()              { return json; }
            @Override public VMessaging getMessaging()    { return messaging; }
            @Override public void register(String path, VHttpService service) {}

            @Override
            @SuppressWarnings("unchecked")
            public <T extends VService> Optional<T> getService(Class<T> type) {
                if (type == VPersistenceService.class) return Optional.of(type.cast(persistenceService));
                if (type == dev.vatn.api.workflow.VEventLog.class) return Optional.of(type.cast(eventLog));
                return Optional.empty();
            }

            @Override public <T extends VService> void registerService(Class<T> type, T instance) {}

            // Stub remaining VNodeContext methods — not exercised by the engine
            @Override public VStream getStream()               { return null; }
            @Override public VPluginRegistry getPluginRegistry(){ return null; }
            @Override public VMemoryChannel getMemory()        { return null; }
            @Override public VConfiguration getConfiguration() { return null; }
            @Override public VClockService getClock()          { return null; }
            @Override public VGuardService getGuard()          { return null; }
            @Override public dev.vatn.api.security.VSecretService getSecrets() { return null; }
            @Override public VDiscovery getDiscovery()         { return null; }
        };
    }
}
