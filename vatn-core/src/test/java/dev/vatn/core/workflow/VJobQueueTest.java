package dev.vatn.core.workflow;

import dev.vatn.api.workflow.VJobHandler;
import dev.vatn.api.workflow.VJobQueue;
import dev.vatn.api.workflow.VRetryPolicy;
import dev.vatn.api.workflow.VTaskInstance;
import dev.vatn.api.workflow.VTaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VJobQueueTest {

    @TempDir Path tempDir;
    private VDagEngineTestHarness harness;
    private VJobQueueImpl queue;

    @BeforeEach
    void setUp() throws Exception {
        harness = new VDagEngineTestHarness(tempDir);
        queue = new VJobQueueImpl(harness.nodeContext(), harness.db());
    }

    @AfterEach
    void tearDown() {
        queue.close();
    }

    @Test
    void submitAndSucceed() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        queue.register("echo", (VJobHandler) ctx -> {
            latch.countDown();
            return ctx.getConf().get("msg");
        });

        String jobId = queue.submit("echo", Map.of("msg", "hello"), VRetryPolicy.NONE, null, null);
        assertNotNull(jobId);

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Job was not executed");

        // Poll until state flips
        awaitState(jobId, VTaskState.SUCCESS, 3000);
        Optional<VTaskInstance> result = queue.getResult(jobId);
        assertTrue(result.isPresent());
        assertEquals(VTaskState.SUCCESS, result.get().state());
    }

    @Test
    void idempotencyKeyDeduplicates() {
        queue.register("noop", ctx -> "ok");

        String id1 = queue.submit("noop", Map.of(), VRetryPolicy.NONE, null, "my-key");
        String id2 = queue.submit("noop", Map.of(), VRetryPolicy.NONE, null, "my-key");
        assertEquals(id1, id2, "Same idempotency key must return same job ID");
    }

    @Test
    void cancelQueuedJob() throws Exception {
        // Register a handler that would block so we can cancel before execution
        CountDownLatch blockLatch = new CountDownLatch(1);
        queue.register("blocking", (VJobHandler) ctx -> {
            blockLatch.await();
            return "done";
        });

        String jobId = queue.submit("blocking", Map.of(), VRetryPolicy.NONE, null, null);

        // Cancel before the poller picks it up (500ms poll interval — cancel immediately)
        boolean cancelled = queue.cancel(jobId);
        assertTrue(cancelled);
        blockLatch.countDown();

        Optional<VTaskInstance> result = queue.getResult(jobId);
        assertTrue(result.isPresent());
        assertEquals(VTaskState.REMOVED, result.get().state());
    }

    @Test
    void ttlExpiresQueuedJob() throws Exception {
        // Don't register a handler — we want the job to sit in QUEUED state
        // and expire due to TTL before the handler could be found
        queue.register("slow-type", (VJobHandler) ctx -> "result");

        String jobId = queue.submit("slow-type", Map.of(), VRetryPolicy.NONE,
            Duration.ofMillis(1), "ttl-test-key-" + System.nanoTime());

        // The TTL is 1ms — wait for the poller to run and expire it
        awaitState(jobId, VTaskState.FAILED, 3000);

        Optional<VTaskInstance> result = queue.getResult(jobId);
        assertTrue(result.isPresent());
        assertEquals(VTaskState.FAILED, result.get().state());
    }

    @Test
    void retryOnFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(1);

        queue.register("flaky", (VJobHandler) ctx -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) throw new RuntimeException("transient error");
            doneLatch.countDown();
            return "recovered";
        });

        VRetryPolicy threeAttempts = new VRetryPolicy(3, 100, 1.0, 1000);
        String jobId = queue.submit("flaky", Map.of(), threeAttempts, null, null);

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Job did not succeed after retries");
        awaitState(jobId, VTaskState.SUCCESS, 3000);
        assertEquals(VTaskState.SUCCESS, queue.getResult(jobId).get().state());
        assertEquals(3, attempts.get());
    }

    @Test
    void unknownJobTypeFailsImmediately() throws Exception {
        String jobId = queue.submit("unregistered-type", Map.of(), VRetryPolicy.NONE, null, null);
        awaitState(jobId, VTaskState.FAILED, 3000);
        assertEquals(VTaskState.FAILED, queue.getResult(jobId).get().state());
    }

    @Test
    void getResultEmptyForUnknownId() {
        assertTrue(queue.getResult("nonexistent-job-id").isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void awaitState(String jobId, VTaskState target, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<VTaskInstance> r = queue.getResult(jobId);
            if (r.isPresent() && r.get().state() == target) return;
            Thread.sleep(100);
        }
        fail("Job " + jobId + " did not reach state " + target + " within " + timeoutMs + "ms. " +
            "Actual: " + queue.getResult(jobId).map(ti -> ti.state().name()).orElse("not found"));
    }
}
