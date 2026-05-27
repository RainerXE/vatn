package dev.vatn.core.workflow;

import dev.vatn.api.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for VDagEngineImpl against an in-memory SQLite database.
 * Covers: serial execution, trigger rules, retry, XCom, fan-out, cancel.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VDagEngineTest {

    @TempDir Path tempDir;

    private VDagEngineTestHarness harness;
    private VDagEngine engine;
    private VDagRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        harness  = new VDagEngineTestHarness(tempDir);
        engine   = harness.engine();
        registry = harness.registry();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ─── basic serial execution ───────────────────────────────────────────────

    @Test @Order(1)
    void singleTaskDag_completesSuccessfully() {
        VDagRun run = engine.trigger("single-test", Map.of(), true);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.SUCCESS)
                      .orElse(false));

        assertEquals(VDagRunState.SUCCESS, engine.getRunById(run.runId()).get().state());
    }

    @Test @Order(2)
    void twoTaskLinearDag_executesInOrder() {
        VDagRun run = engine.trigger("linear-test", Map.of(), true);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.SUCCESS)
                      .orElse(false));

        List<VTaskInstance> instances = engine.getTaskInstances(run.runId());
        assertEquals(2, instances.size());
        instances.forEach(ti -> assertEquals(VTaskState.SUCCESS, ti.state()));
    }

    @Test @Order(3)
    void fanOutDag_allBranchesComplete() {
        VDagRun run = engine.trigger("fanout-test", Map.of(), true);

        await().atMost(15, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.SUCCESS)
                      .orElse(false));

        long successCount = engine.getTaskInstances(run.runId()).stream()
                .filter(ti -> ti.state() == VTaskState.SUCCESS).count();
        assertEquals(4, successCount);
    }

    // ─── trigger rules ────────────────────────────────────────────────────────

    @Test @Order(4)
    void triggerRule_ALWAYS_runsEvenIfUpstreamFailed() {
        AtomicInteger cleanupCalled = new AtomicInteger(0);

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "always-fail"; }
            public String execute(VTaskContext ctx) throws Exception {
                throw new RuntimeException("intentional failure");
            }
        });
        registry.registerOperator(new VOperator() {
            public String operatorType() { return "counter"; }
            public String execute(VTaskContext ctx) {
                cleanupCalled.incrementAndGet();
                return "cleanup-done";
            }
        });

        VRetryPolicy noRetry = new VRetryPolicy(1, 100, 1.0, 100);
        VDagTask failTask = new VDagTask("fail", "always-fail", Set.of(), VTriggerRule.ALL_SUCCESS,
                noRetry, VPool.DEFAULT_POOL, 0L, 0, false, 0L, null, Map.of());
        VDagTask cleanupTask = new VDagTask("cleanup", "counter", Set.of("fail"),
                VTriggerRule.ALWAYS, noRetry, VPool.DEFAULT_POOL, 0L, 0, false, 0L, null, Map.of());

        registry.register(VDag.manual("trigger-always-test", "Trigger always test",
                Map.of("fail", failTask, "cleanup", cleanupTask)));

        VDagRun run = engine.trigger("trigger-always-test", Map.of(), true);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.FAILED || r.state() == VDagRunState.SUCCESS)
                      .orElse(false));

        assertEquals(1, cleanupCalled.get(), "ALWAYS trigger task must run even after upstream failure");
    }

    // ─── XCom ────────────────────────────────────────────────────────────────

    @Test @Order(5)
    void xcom_upstreamReturnValueReadableByDownstream() {
        AtomicInteger receivedValue = new AtomicInteger(-1);

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "producer"; }
            public String execute(VTaskContext ctx) {
                ctx.getXCom().pushReturn(ctx.getTaskId(), "42");
                return "42";
            }
        });
        registry.registerOperator(new VOperator() {
            public String operatorType() { return "consumer"; }
            public String execute(VTaskContext ctx) {
                String val = ctx.getXCom().pullReturn("producer").orElse("-1");
                receivedValue.set(Integer.parseInt(val));
                return val;
            }
        });

        Map<String, VDagTask> tasks = new LinkedHashMap<>();
        tasks.put("producer", VDagTask.of("producer", "producer", Set.of(), Map.of()));
        tasks.put("consumer", VDagTask.of("consumer", "consumer", Set.of("producer"), Map.of()));
        registry.register(VDag.manual("xcom-test", "XCom test", tasks));

        VDagRun run = engine.trigger("xcom-test", Map.of(), true);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.SUCCESS)
                      .orElse(false));

        assertEquals(42, receivedValue.get(), "Consumer must read XCom value set by producer");
    }

    // ─── retry ───────────────────────────────────────────────────────────────

    @Test @Order(6)
    void retryPolicy_taskSucceedsOnSecondAttempt() {
        AtomicInteger attempts = new AtomicInteger(0);

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "flaky"; }
            public String execute(VTaskContext ctx) throws Exception {
                int n = attempts.incrementAndGet();
                if (n == 1) throw new RuntimeException("first attempt fails");
                return "success-on-attempt-" + n;
            }
        });

        VRetryPolicy retry2 = new VRetryPolicy(2, 100, 1.0, 100);
        VDagTask flaky = new VDagTask("flaky", "flaky", Set.of(), VTriggerRule.ALL_SUCCESS,
                retry2, VPool.DEFAULT_POOL, 0L, 0, false, 0L, null, Map.of());
        registry.register(VDag.manual("retry-test", "Retry test", Map.of("flaky", flaky)));

        VDagRun run = engine.trigger("retry-test", Map.of(), true);

        await().atMost(15, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.SUCCESS)
                      .orElse(false));

        assertEquals(2, attempts.get(), "Task must be retried exactly once before succeeding");
        assertEquals(VDagRunState.SUCCESS, engine.getRunById(run.runId()).get().state());
    }

    // ─── cancel ───────────────────────────────────────────────────────────────

    @Test @Order(7)
    void cancel_transitionsRunToCanceled() throws Exception {
        registry.registerOperator(new VOperator() {
            public String operatorType() { return "slow"; }
            public String execute(VTaskContext ctx) throws Exception {
                Thread.sleep(60_000); // will be interrupted by cancel
                return "done";
            }
        });
        VDagTask slow = new VDagTask("slow", "slow", Set.of(), VTriggerRule.ALL_SUCCESS,
                VRetryPolicy.NONE, VPool.DEFAULT_POOL, 0L, 0, false, 0L, null, Map.of());
        registry.register(VDag.manual("cancel-test", "Cancel test", Map.of("slow", slow)));

        VDagRun run = engine.trigger("cancel-test", Map.of(), true);

        await().atMost(5, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.RUNNING)
                      .orElse(false));

        engine.cancel(run.runId());

        await().atMost(8, TimeUnit.SECONDS).until(() ->
                engine.getRunById(run.runId())
                      .map(r -> r.state() == VDagRunState.CANCELED)
                      .orElse(false));

        assertEquals(VDagRunState.CANCELED, engine.getRunById(run.runId()).get().state());
    }

    // ─── fanOut API ───────────────────────────────────────────────────────────

    @Test @Order(8)
    void fanOutApi_launchesMultipleRuns() {
        // maxActiveRuns=0 (unlimited) required for parallel fanOut — VDag.manual sets maxActiveRuns=1
        VDag dag = new VDag("fanout-api-test", "Fan-out test", null, 0, false, 0, Set.of(),
                Map.of("t1", VDagTask.of("t1", "noop", Set.of(), Map.of())), Map.of());
        registry.register(dag);

        List<VDagRun> runs = engine.fanOut("fanout-api-test",
                List.of(Map.of("param", "a"), Map.of("param", "b"), Map.of("param", "c")));

        assertEquals(3, runs.size());
        Set<String> runIds = new HashSet<>();
        runs.forEach(r -> runIds.add(r.runId()));
        assertEquals(3, runIds.size(), "Each fanOut run must have a unique runId");

        for (VDagRun run : runs) {
            await().atMost(10, TimeUnit.SECONDS).until(() ->
                    engine.getRunById(run.runId())
                          .map(r -> r.state() == VDagRunState.SUCCESS)
                          .orElse(false));
        }
    }
}
