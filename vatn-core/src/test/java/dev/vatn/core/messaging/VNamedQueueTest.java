package dev.vatn.core.messaging;

import dev.vatn.api.workflow.VClaimOptions;
import dev.vatn.api.workflow.VNamedQueue;
import dev.vatn.api.workflow.VQueueJob;
import dev.vatn.api.workflow.VQueueService;
import dev.vatn.core.memory.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VNamedQueueTest {

    @TempDir Path tempDir;
    private DatabaseManager db;
    private VQueueService queueService;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath();
        db = new DatabaseManager(jdbcUrl);
        db.registerSchemaContributor(new VatnMessagingSchemaContributor());

        try (Connection conn = db.getConnection()) {
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS test_orders(id INTEGER PRIMARY KEY AUTOINCREMENT)");
        }

        queueService = new VQueueServiceImpl(db);
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void enqueueAndConsume_happyPath() throws Exception {
        VNamedQueue queue = queueService.queue("emails",
            VClaimOptions.defaults().withPollIntervalMs(50).withVisibility(Duration.ofSeconds(30)));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> handledPayload = new AtomicReference<>();

        String id = queue.enqueue("{\"to\":\"alice@example.com\"}");
        queue.consume("worker-1", job -> {
            handledPayload.set(job.payload());
            latch.countDown();
        }, VClaimOptions.defaults().withPollIntervalMs(50).withVisibility(Duration.ofSeconds(30)));

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Handler did not run");
        assertEquals("{\"to\":\"alice@example.com\"}", handledPayload.get());

        awaitJobState(queue, id, VQueueJob.State.DONE, 3000);
        assertEquals(VQueueJob.State.DONE, queue.getJob(id).orElseThrow().state());
    }

    @Test
    void priorityOrderingRespected() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30));
        VNamedQueue queue = queueService.queue("priority-test", opts);

        queue.enqueue("low", 0);
        queue.enqueue("mid", 5);
        queue.enqueue("high", 10);

        List<VQueueJob> batch = queue.claimBatch("w1", 3, opts);
        assertEquals(3, batch.size());
        assertEquals(10, batch.get(0).priority());
        assertEquals(5, batch.get(1).priority());
        assertEquals(0, batch.get(2).priority());
    }

    @Test
    void delayedJobNotClaimedEarly() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30));
        VNamedQueue queue = queueService.queue("delayed-test", opts);

        String id = queue.enqueueAt("{\"task\":\"deferred\"}", Instant.now().plusSeconds(10));

        List<VQueueJob> empty = queue.claimBatch("w1", 1, opts);
        assertTrue(empty.isEmpty(), "Delayed job should not be claimable before run_at");

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_named_queue_jobs SET run_at=? WHERE id=?")) {
            ps.setString(1, Instant.now().minusSeconds(1).toString());
            ps.setString(2, id);
            ps.executeUpdate();
        }

        List<VQueueJob> claimed = queue.claimBatch("w1", 1, opts);
        assertFalse(claimed.isEmpty(), "Job should be claimable after backdating run_at");
        assertEquals(id, claimed.get(0).id());
    }

    @Test
    void visibilityTimeoutReclaimsJob() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30));
        VNamedQueue queue = queueService.queue("visibility-test", opts);

        String id = queue.enqueue("{\"task\":\"timeout-me\"}");
        List<VQueueJob> first = queue.claimBatch("w1", 1, opts);
        assertFalse(first.isEmpty());
        assertEquals(id, first.get(0).id());

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_named_queue_jobs SET claim_expires_at=? WHERE id=?")) {
            ps.setString(1, Instant.now().minusSeconds(1).toString());
            ps.setString(2, id);
            ps.executeUpdate();
        }

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_named_queue_jobs
                SET state='PENDING', worker_id=NULL, claim_expires_at=NULL
                WHERE state='CLAIMED' AND claim_expires_at IS NOT NULL AND claim_expires_at < ?
                """)) {
            ps.setString(1, Instant.now().toString());
            ps.executeUpdate();
        }

        Optional<VQueueJob> reclaimed = queue.getJob(id);
        assertTrue(reclaimed.isPresent());
        assertEquals(VQueueJob.State.PENDING, reclaimed.get().state());

        List<VQueueJob> second = queue.claimBatch("w2", 1, opts);
        assertFalse(second.isEmpty(), "Job should be reclaimable after visibility expiry");
        assertEquals(id, second.get(0).id());
    }

    @Test
    void nackSchedulesRetry() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30))
            .withBackoff(Duration.ofSeconds(5));
        VNamedQueue queue = queueService.queue("nack-retry-test", opts);

        String id = queue.enqueue("{\"task\":\"flaky\"}");
        List<VQueueJob> claimed = queue.claimBatch("w1", 1, opts);
        assertFalse(claimed.isEmpty());

        boolean nacked = queue.nack(id, "w1", "transient error");
        assertTrue(nacked);

        VQueueJob job = queue.getJob(id).orElseThrow();
        assertEquals(VQueueJob.State.PENDING, job.state());
        assertEquals(1, job.attempts());
        assertTrue(job.runAt().isAfter(Instant.now()),
            "run_at should be in the future after nack with backoff");
    }

    @Test
    void nackExhaustsRetries_movesToDead() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30))
            .withMaxAttempts(2)
            .withBackoff(Duration.ofMillis(0));
        VNamedQueue queue = queueService.queue("exhaust-retry-test", opts);

        String id = queue.enqueue("{\"task\":\"will-fail\"}");

        List<VQueueJob> first = queue.claimBatch("w1", 1, opts);
        queue.nack(id, "w1", "error 1");

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_named_queue_jobs SET run_at=? WHERE id=?")) {
            ps.setString(1, Instant.now().minusSeconds(1).toString());
            ps.setString(2, id);
            ps.executeUpdate();
        }

        List<VQueueJob> second = queue.claimBatch("w1", 1, opts);
        assertFalse(second.isEmpty());
        queue.nack(id, "w1", "error 2");

        VQueueJob job = queue.getJob(id).orElseThrow();
        assertEquals(VQueueJob.State.DEAD, job.state());
    }

    @Test
    void deadLetterQueue_receivesExhaustedJob() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30))
            .withMaxAttempts(1)
            .withBackoff(Duration.ofMillis(0))
            .withDeadLetterQueue("emails.dlq");
        VNamedQueue queue = queueService.queue("dlq-source", opts);
        VNamedQueue dlq = queueService.queue("emails.dlq",
            VClaimOptions.defaults().withPollIntervalMs(50).withVisibility(Duration.ofSeconds(30)));

        String id = queue.enqueue("{\"to\":\"bob@example.com\"}");
        List<VQueueJob> claimed = queue.claimBatch("w1", 1, opts);
        assertFalse(claimed.isEmpty());
        queue.nack(id, "w1", "permanent failure");

        VQueueJob sourceJob = queue.getJob(id).orElseThrow();
        assertEquals(VQueueJob.State.DEAD, sourceJob.state());

        List<VQueueJob> dlqJobs = dlq.claimBatch("dlq-worker", 10,
            VClaimOptions.defaults().withPollIntervalMs(50).withVisibility(Duration.ofSeconds(30)));
        assertFalse(dlqJobs.isEmpty(), "DLQ should have received the exhausted job");
        assertEquals("{\"to\":\"bob@example.com\"}", dlqJobs.get(0).payload());
    }

    @Test
    void ackWithResult_waitResultReturns() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30));
        VNamedQueue queue = queueService.queue("result-test", opts);

        String id = queue.enqueue("{\"task\":\"send-email\"}");
        List<VQueueJob> claimed = queue.claimBatch("w1", 1, opts);
        assertFalse(claimed.isEmpty());

        queue.ack(id, "w1", "sent");

        Optional<String> result = queue.waitResult(id, Duration.ofSeconds(2));
        assertTrue(result.isPresent(), "waitResult should return the stored result");
        assertEquals("sent", result.get());
    }

    @Test
    void cancelPendingJob() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30));
        VNamedQueue queue = queueService.queue("cancel-test", opts);

        String id = queue.enqueue("{\"task\":\"to-be-cancelled\"}");
        boolean cancelled = queue.cancel(id);
        assertTrue(cancelled);

        VQueueJob job = queue.getJob(id).orElseThrow();
        assertEquals(VQueueJob.State.DEAD, job.state());
    }

    @Test
    void atomicEnqueueOnExternalConnection() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30));
        VNamedQueue queue = queueService.queue("atomic-test", opts);

        String jobId;
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO test_orders DEFAULT VALUES")) {
                ps.executeUpdate();
            }
            jobId = queue.enqueue("{\"order\":\"bundled\"}", conn);
            conn.commit();
        }

        assertNotNull(jobId);
        Optional<VQueueJob> job = queue.getJob(jobId);
        assertTrue(job.isPresent());
        assertEquals(VQueueJob.State.PENDING, job.get().state());

        try (Connection conn = db.getConnection();
             ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM test_orders")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void listDeadLetters_returnsOnlyDeadJobs() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30))
            .withMaxAttempts(1)
            .withBackoff(Duration.ofMillis(0));
        VNamedQueue queue = queueService.queue("dead-list-test", opts);

        String id1 = queue.enqueue("job1");
        String id2 = queue.enqueue("job2");
        String id3 = queue.enqueue("job3");

        List<VQueueJob> batch = queue.claimBatch("w1", 2, opts);
        assertEquals(2, batch.size());
        for (VQueueJob job : batch) {
            queue.nack(job.id(), "w1", "fatal");
        }

        queue.claimBatch("w1", 1, opts);
        queue.ack(id3, "w1");

        List<VQueueJob> dead = queue.listDeadLetters();
        assertEquals(2, dead.size());
        assertTrue(dead.stream().allMatch(j -> j.state() == VQueueJob.State.DEAD));
    }

    @Test
    void purgeRemovesOldDoneJobs() throws Exception {
        VClaimOptions opts = VClaimOptions.defaults()
            .withPollIntervalMs(50)
            .withVisibility(Duration.ofSeconds(30));
        VNamedQueue queue = queueService.queue("purge-test", opts);

        for (int i = 0; i < 3; i++) {
            String id = queue.enqueue("job-" + i);
            List<VQueueJob> claimed = queue.claimBatch("w1", 1, opts);
            assertFalse(claimed.isEmpty());
            queue.ack(id, "w1");
        }

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_named_queue_jobs SET created_at=? WHERE queue='purge-test'")) {
            ps.setString(1, Instant.now().minusSeconds(10).toString());
            ps.executeUpdate();
        }

        int purged = queue.purge(Duration.ZERO);
        assertEquals(3, purged);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM vatn_named_queue_jobs WHERE queue='purge-test'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void awaitJobState(VNamedQueue queue, String id, VQueueJob.State target, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<VQueueJob> job = queue.getJob(id);
            if (job.isPresent() && job.get().state() == target) return;
            Thread.sleep(50);
        }
        fail("Job " + id + " did not reach state " + target + " within " + timeoutMs + "ms. "
            + "Actual: " + queue.getJob(id).map(j -> j.state().name()).orElse("not found"));
    }
}
