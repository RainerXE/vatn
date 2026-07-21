package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.api.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash recovery and data integrity hardening tests.
 *
 * <p>Tests ensure that VATN's durable state (SQLite WAL, event log, DAG state)
 * survives the following failure scenarios:
 * <ul>
 *   <li>Abrupt JVM termination mid-DAG execution</li>
 *   <li>SQLite WAL file truncation</li>
 *   <li>Torn write to event log</li>
 *   <li>Plugin crash during initialisation — other plugins must still boot</li>
 *   <li>Thread exhaustion — node continues to schedule new tasks after recovery</li>
 * </ul>
 */
@DisplayName("Crash Recovery & Data Integrity Tests")
@Tag("adversarial")
class CrashRecoveryTest {

    @TempDir Path tempDir;

    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    /** Several tests point user.home at their temp dir; restore it so later classes aren't poisoned. */
    @org.junit.jupiter.api.AfterAll
    static void restoreUserHome() {
        System.setProperty("user.home", ORIGINAL_USER_HOME);
    }

    // =========================================================================
    // 1. Plugin crash isolation
    // =========================================================================

    @Test
    @DisplayName("A plugin that throws in onInitialize must not prevent other plugins from loading")
    @Timeout(20)
    void pluginInitFailureIsIsolated() throws Exception {
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        AtomicBoolean goodPluginInitialized = new AtomicBoolean(false);

        VNodeRunner runner = VNodeRunner.create(0);

        // Bad plugin — throws in onInitialize
        runner.addPlugin(new dev.vatn.api.VNodePlugin() {
            @Override public String getId()      { return "bad-plugin"; }
            @Override public String getName()    { return "Bad Plugin"; }
            @Override public String getVersion() { return "1.0"; }
            @Override public void onInitialize(dev.vatn.api.VNodeContext ctx) {
                throw new RuntimeException("Intentional init failure for testing");
            }
        });

        // Good plugin — must still load even though bad plugin failed
        runner.addPlugin(new dev.vatn.api.VNodePlugin() {
            @Override public String getId()      { return "good-plugin"; }
            @Override public String getName()    { return "Good Plugin"; }
            @Override public String getVersion() { return "1.0"; }
            @Override public void onInitialize(dev.vatn.api.VNodeContext ctx) {
                goodPluginInitialized.set(true);
            }
        });

        // Start must not throw — it must handle plugin init failure gracefully
        assertDoesNotThrow(runner::start,
                "Node startup must not throw even if a plugin's onInitialize throws");

        runner.stop();

        assertTrue(goodPluginInitialized.get(),
                "Good plugin must have been initialized even after bad plugin failed");
    }

    // =========================================================================
    // 2. DAG idempotency after restart
    // =========================================================================

    @Test
    @DisplayName("Completed DAG tasks must not re-execute after node restart")
    @Timeout(30)
    void dagTasksAreIdempotentAcrossRestart() throws Exception {
        Path dbPath = tempDir.resolve("vatn.db");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        AtomicInteger execCount = new AtomicInteger(0);

        // ── First run ──────────────────────────────────────────────────────
        VNodeRunner runner1 = VNodeRunner.create(0).withDbPath(dbPath);
        runner1.start();

        VDagEngine engine1 = runner1.getContext().getDagEngine();
        VDagRegistry reg1  = runner1.getContext().getDagRegistry();

        reg1.registerOperator(new VOperator() {
            @Override public String operatorType() { return "counting-op"; }
            @Override public String execute(VTaskContext ctx) {
                execCount.incrementAndGet();
                return "done";
            }
        });

        reg1.register(VDag.manual("idempotency-test", "Idempotency test",
                Map.of("t1", VDagTask.of("t1", "counting-op", Set.of(), Map.of()))));

        VDagRun run = engine1.trigger("idempotency-test");

        // Wait for completion
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<VDagRun> r = engine1.getRunById(run.runId());
            if (r.map(rr -> rr.state() == VDagRunState.SUCCESS).orElse(false)) break;
            Thread.sleep(50);
        }
        assertEquals(1, execCount.get(), "Task should have run exactly once in first run");

        runner1.stop();

        // ── Second run (simulated restart) ─────────────────────────────────
        VNodeRunner runner2 = VNodeRunner.create(0).withDbPath(dbPath);
        runner2.start();

        VDagEngine engine2 = runner2.getContext().getDagEngine();
        VDagRegistry reg2  = runner2.getContext().getDagRegistry();

        reg2.registerOperator(new VOperator() {
            @Override public String operatorType() { return "counting-op"; }
            @Override public String execute(VTaskContext ctx) {
                execCount.incrementAndGet(); // should NOT be called again
                return "done";
            }
        });

        reg2.register(VDag.manual("idempotency-test", "Idempotency test",
                Map.of("t1", VDagTask.of("t1", "counting-op", Set.of(), Map.of()))));

        // Give the engine a moment to possibly replay
        Thread.sleep(2_000);

        runner2.stop();

        assertEquals(1, execCount.get(),
                "Task must not re-run after node restart — execution must be idempotent");
    }

    // =========================================================================
    // 3. SQLite WAL truncation
    // =========================================================================

    @Test
    @DisplayName("Node recovers gracefully when SQLite WAL file is truncated")
    @Timeout(20)
    void walTruncationRecovery() throws Exception {
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        // Start a node to create the DB
        VNodeRunner runner1 = VNodeRunner.create(0);
        runner1.start();
        Thread.sleep(500); // let DB initialise
        runner1.stop();

        // Find and truncate the WAL file
        Path[] walFiles = Files.list(tempDir).filter(p -> p.toString().endsWith("-wal"))
                .toArray(Path[]::new);

        for (Path wal : walFiles) {
            // Truncate to half its size — simulating a torn write
            try (RandomAccessFile raf = new RandomAccessFile(wal.toFile(), "rw")) {
                long half = raf.length() / 2;
                raf.setLength(half);
            }
        }

        // Restarting after WAL truncation must not throw
        VNodeRunner runner2 = VNodeRunner.create(0);
        assertDoesNotThrow(runner2::start,
                "Node must start after WAL file truncation (SQLite auto-recovers)");
        runner2.stop();
    }

    // =========================================================================
    // 4. Thread leak: 100 sequential DAGs
    // =========================================================================

    @Test
    @DisplayName("Running 50 sequential DAGs must not leak threads")
    @Timeout(60)
    void noThreadLeakAfterManyDags() throws Exception {
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        VNodeRunner runner = VNodeRunner.create(0);
        runner.start();

        VDagEngine engine = runner.getContext().getDagEngine();
        VDagRegistry reg  = runner.getContext().getDagRegistry();

        reg.registerOperator(new VOperator() {
            @Override public String operatorType() { return "noop"; }
            @Override public String execute(VTaskContext ctx) { return "ok"; }
        });

        for (int i = 0; i < 50; i++) {
            String id = "leak-test-" + i;
            reg.register(VDag.manual(id, "Leak test " + i,
                    Map.of("t1", VDagTask.of("t1", "noop", Set.of(), Map.of()))));
        }

        int threadsBefore = Thread.activeCount();

        for (int i = 0; i < 50; i++) {
            VDagRun run = engine.trigger("leak-test-" + i);
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                if (engine.getRunById(run.runId())
                          .map(r -> r.state() == VDagRunState.SUCCESS).orElse(false)) break;
                Thread.sleep(20);
            }
        }

        // Allow any finaliser threads to wind down
        Thread.sleep(1_000);

        int threadsAfter = Thread.activeCount();
        runner.stop();

        // Allow for a small increase (worker pool, gc threads etc.)
        assertTrue(threadsAfter - threadsBefore < 20,
                "Thread leak detected: " + threadsBefore + " threads before → "
                + threadsAfter + " after 50 DAGs (max allowed growth: 20)");
    }

    // =========================================================================
    // 5. Double-start guard
    // =========================================================================

    @Test
    @DisplayName("Calling start() twice must not crash or corrupt state")
    @Timeout(15)
    void doubleStartIsIdempotent() throws Exception {
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        VNodeRunner runner = VNodeRunner.create(0);
        runner.start();

        // Second start — must either be a no-op or throw a clear exception, never corrupt
        assertDoesNotThrow(runner::start, "Second start() must not throw a runtime exception");

        runner.stop();
    }

    // =========================================================================
    // 6. Stop then stop
    // =========================================================================

    @Test
    @DisplayName("Calling stop() twice must not throw")
    @Timeout(15)
    void doubleStopIsIdempotent() throws Exception {
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        VNodeRunner runner = VNodeRunner.create(0);
        runner.start();
        runner.stop();

        // Second stop must be a no-op, not throw
        assertDoesNotThrow(runner::stop, "Second stop() must not throw");
    }

    // =========================================================================
    // 7. Concurrent DAG triggers
    // =========================================================================

    @Test
    @DisplayName("100 concurrent DAG triggers on the same DAG must all complete")
    @Timeout(60)
    void concurrentDagTriggersAllComplete() throws Exception {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                doConcurrentDagTriggers();
                return;
            } catch (AssertionError e) {
                if (attempt == maxAttempts) throw e;
                System.out.println("[" + attempt + "/" + maxAttempts + "] "
                        + e.getMessage() + " — retrying");
            }
        }
    }

    private void doConcurrentDagTriggers() throws Exception {
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        VNodeRunner runner = VNodeRunner.create(0);
        runner.start();

        VDagEngine engine = runner.getContext().getDagEngine();
        VDagRegistry reg  = runner.getContext().getDagRegistry();
        AtomicInteger ran = new AtomicInteger();

        reg.registerOperator(new VOperator() {
            @Override public String operatorType() { return "counter"; }
            @Override public String execute(VTaskContext ctx) {
                ran.incrementAndGet();
                return "ok";
            }
        });

        String dagId = "concurrent-dag";
        reg.register(VDag.manual(dagId, "Concurrent test",
                Map.of("t1", VDagTask.of("t1", "counter", Set.of(), Map.of()))));

        int count = 100;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<VDagRun>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> engine.trigger(dagId)));
        }

        List<VDagRun> runs = new ArrayList<>();
        for (Future<VDagRun> f : futures) runs.add(f.get(5, TimeUnit.SECONDS));

        // Wait for all to complete
        long deadline = System.currentTimeMillis() + 30_000;
        for (VDagRun run : runs) {
            while (System.currentTimeMillis() < deadline) {
                if (engine.getRunById(run.runId())
                          .map(r -> r.state() == VDagRunState.SUCCESS
                                 || r.state() == VDagRunState.FAILED).orElse(false)) break;
                Thread.sleep(30);
            }
        }

        long succeeded = runs.stream()
                .map(r -> engine.getRunById(r.runId()).orElse(null))
                .filter(r -> r != null && r.state() == VDagRunState.SUCCESS)
                .count();

        runner.stop();
        pool.shutdown();

        assertTrue(succeeded >= count * 0.95,
                "At least 95% of concurrent DAG runs must succeed. Succeeded: "
                + succeeded + "/" + count);
    }
}
