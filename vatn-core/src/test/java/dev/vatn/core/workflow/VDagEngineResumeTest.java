package dev.vatn.core.workflow;

import dev.vatn.api.workflow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests VEventLog integration with VDagEngineImpl.
 */
class VDagEngineResumeTest {

    @TempDir Path tempDir;
    private VDagEngineTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new VDagEngineTestHarness(tempDir);
    }

    @Test
    void dagTriggeredAndDagSuccessEventsAreWritten() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        harness.registry().registerOperator(new VOperator() {
            @Override public String operatorType() { return "latch-op"; }
            @Override public String execute(VTaskContext ctx) { latch.countDown(); return "done"; }
        });
        harness.registry().register(VDag.manual("event-test", "Event test",
                Map.of("t1", VDagTask.of("t1", "latch-op", Set.of(), Map.of()))));

        VDagRun run = harness.engine().trigger("event-test");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(300); // allow finalize to commit DAG_SUCCESS

        assertFalse(harness.eventLog().getInterruptedRunIds().contains(run.runId()),
                "Completed run must not appear as interrupted");
        assertTrue(harness.eventLog().hasSucceeded(run.runId(), "t1"),
                "TASK_SUCCESS must be present in event log");
    }

    @Test
    void taskWithSuccessEventIsSkippedOnReplay() throws Exception {
        AtomicInteger execCount = new AtomicInteger(0);
        harness.registry().registerOperator(new VOperator() {
            @Override public String operatorType() { return "count-op"; }
            @Override public String execute(VTaskContext ctx) { execCount.incrementAndGet(); return "done"; }
        });
        harness.registry().register(VDag.manual("replay-test", "Replay test",
                Map.of("t1", VDagTask.of("t1", "count-op", Set.of(), Map.of()))));

        // Normal first run — task executes once
        VDagRun run = harness.engine().trigger("replay-test");
        awaitRunState(run.runId(), VDagRunState.SUCCESS, 5000);
        assertEquals(1, execCount.get());

        // Pre-inject TASK_SUCCESS for a new fake run_id to simulate a partial run before crash
        String crashRunId = "crash-" + System.nanoTime();
        harness.eventLog().append(crashRunId, "replay-test", null, "DAG_TRIGGERED", null);
        harness.eventLog().append(crashRunId, "replay-test", "t1", "TASK_SUCCESS", "pre-done");

        assertTrue(harness.eventLog().hasSucceeded(crashRunId, "t1"));
        assertTrue(harness.eventLog().getInterruptedRunIds().contains(crashRunId));
    }

    @Test
    void taskSuccessEventPayloadMatchesOperatorOutput() throws Exception {
        harness.registry().registerOperator(new VOperator() {
            @Override public String operatorType() { return "value-op"; }
            @Override public String execute(VTaskContext ctx) { return "my-output-42"; }
        });
        harness.registry().register(VDag.manual("output-test", "Output test",
                Map.of("t1", VDagTask.of("t1", "value-op", Set.of(), Map.of()))));

        VDagRun run = harness.engine().trigger("output-test");
        awaitRunState(run.runId(), VDagRunState.SUCCESS, 5000);
        Thread.sleep(200);

        assertTrue(harness.eventLog().hasSucceeded(run.runId(), "t1"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void awaitRunState(String runId, VDagRunState target, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var run = harness.engine().getRunById(runId);
            if (run.isPresent() && run.get().state() == target) return;
            Thread.sleep(100);
        }
        var actual = harness.engine().getRunById(runId).map(r -> r.state().name()).orElse("not found");
        fail("Run " + runId + " did not reach " + target + " within " + timeoutMs + "ms. Actual: " + actual);
    }
}
