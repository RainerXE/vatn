package dev.vatn.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.VJobHandler;
import dev.vatn.api.workflow.VJobQueue;
import dev.vatn.api.workflow.VRetryPolicy;
import dev.vatn.api.workflow.VTaskContext;
import dev.vatn.api.workflow.VTaskInstance;
import dev.vatn.api.workflow.VTaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DB-backed background job queue. Jobs are lightweight, single-operator tasks.
 * Use VDagEngine for multi-step workflows; use this for simple fire-and-monitor operations.
 *
 * <p>Register handlers before submitting jobs:
 * <pre>
 *   VJobQueueImpl q = (VJobQueueImpl) context.getService(VJobQueue.class).get();
 *   q.register("send-email", ctx -> { ...send...; return "sent"; });
 * </pre>
 */
public class VJobQueueImpl implements VJobQueue, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VJobQueueImpl.class);
    private static final String DAG_ID = "__job_queue__";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_INTERVAL_MS = 500;

    private final VPersistenceService db;
    private final VNodeContext nodeContext;
    private final Map<String, VJobHandler> handlers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vatn-job-queue-poller");
        t.setDaemon(true);
        return t;
    });

    public VJobQueueImpl(VNodeContext nodeContext, VPersistenceService db) {
        this.nodeContext = nodeContext;
        this.db = db;
        scheduler.scheduleAtFixedRate(this::poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Registers a handler for a given job type. Must be called before any jobs of that type are submitted. */
    public void register(String jobType, VJobHandler handler) {
        handlers.put(jobType, handler);
    }

    @Override
    public String submit(String jobType, Map<String, String> payload, VRetryPolicy retryPolicy,
                         Duration ttl, String idempotencyKey) {
        // Idempotency check
        if (idempotencyKey != null) {
            Optional<String> existing = findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }

        String jobId = UUID.randomUUID().toString();
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize job payload", e);
        }

        String expiresAt = ttl != null ? Instant.now().plus(ttl).toString() : null;

        String now = Instant.now().toString();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO vatn_job_queue
                  (job_id, job_type, payload, state, max_attempts,
                   initial_delay_ms, backoff_multiplier, max_delay_ms,
                   idempotency_key, expires_at, created_at, enqueue_after)
                VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, jobId);
            ps.setString(2, jobType);
            ps.setString(3, payloadJson);
            ps.setInt(4, retryPolicy.maxAttempts());
            ps.setLong(5, retryPolicy.initialDelayMs());
            ps.setDouble(6, retryPolicy.backoffMultiplier());
            ps.setLong(7, retryPolicy.maxDelayMs());
            ps.setString(8, idempotencyKey);
            ps.setString(9, expiresAt);
            ps.setString(10, now);
            ps.setString(11, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to submit job", e);
        }
        log.debug("[JOB-QUEUE] Submitted job {} (type={})", jobId, jobType);
        return jobId;
    }

    @Override
    public Optional<VTaskInstance> getResult(String jobId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT job_type, state, try_number, created_at, expires_at FROM vatn_job_queue WHERE job_id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                VTaskState state = mapState(rs.getString("state"));
                int tryNumber = rs.getInt("try_number");
                String createdAtStr = rs.getString("created_at");
                Instant startDate = createdAtStr != null ? Instant.parse(createdAtStr) : null;
                Instant endDate = state == VTaskState.SUCCESS || state == VTaskState.FAILED ? Instant.now() : null;
                return Optional.of(new VTaskInstance(
                    rs.getString("job_type"), jobId, DAG_ID,
                    state, tryNumber, startDate, endDate, nodeContext.getNodeId()));
            }
        } catch (SQLException e) {
            log.error("[JOB-QUEUE] Failed to get result for job {}", jobId, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean cancel(String jobId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_job_queue SET state = 'CANCELLED' WHERE job_id = ? AND state IN ('QUEUED', 'RUNNING')")) {
            ps.setString(1, jobId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("[JOB-QUEUE] Failed to cancel job {}", jobId, e);
            return false;
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private void poll() {
        try {
            expireTtlJobs();
            dispatchReady();
        } catch (Exception e) {
            log.error("[JOB-QUEUE] Poller error", e);
        }
    }

    private void expireTtlJobs() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_job_queue SET state = 'FAILED', error = 'TTL_EXPIRED'
                WHERE state = 'QUEUED' AND expires_at IS NOT NULL AND expires_at < ?
                """)) {
            ps.setString(1, Instant.now().toString());
            int expired = ps.executeUpdate();
            if (expired > 0) log.debug("[JOB-QUEUE] Expired {} TTL jobs", expired);
        } catch (SQLException e) {
            log.error("[JOB-QUEUE] TTL expiry check failed", e);
        }
    }

    private void dispatchReady() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT job_id, job_type, payload, try_number,
                       max_attempts, initial_delay_ms, backoff_multiplier, max_delay_ms
                FROM vatn_job_queue
                WHERE state = 'QUEUED' AND enqueue_after <= ?
                LIMIT 50
                """)) {
            ps.setString(1, Instant.now().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String jobId   = rs.getString("job_id");
                    String jobType = rs.getString("job_type");
                    String payload = rs.getString("payload");
                    int tryNum     = rs.getInt("try_number");
                    VRetryPolicy policy = new VRetryPolicy(
                        rs.getInt("max_attempts"),
                        rs.getLong("initial_delay_ms"),
                        rs.getDouble("backoff_multiplier"),
                        rs.getLong("max_delay_ms"));

                    // Claim atomically — skip if another thread already claimed it
                    if (!claimJob(jobId)) continue;

                    VJobHandler handler = handlers.get(jobType);
                    if (handler == null) {
                        failJob(jobId, "No handler registered for job type: " + jobType);
                        continue;
                    }

                    final String payloadCopy = payload;
                    final int currentTry = tryNum;
                    Thread.ofVirtual().name("vatn-job-" + jobId.substring(0, 8))
                        .start(() -> executeJob(jobId, jobType, payloadCopy, currentTry, policy, handler));
                }
            }
        } catch (SQLException e) {
            log.error("[JOB-QUEUE] Dispatch poll failed", e);
        }
    }

    private boolean claimJob(String jobId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_job_queue SET state = 'RUNNING' WHERE job_id = ? AND state = 'QUEUED'")) {
            ps.setString(1, jobId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void executeJob(String jobId, String jobType, String payloadJson,
                            int tryNumber, VRetryPolicy policy, VJobHandler handler) {
        log.info("[JOB-QUEUE] Executing job {} (type={}, try={})", jobId, jobType, tryNumber);
        Map<String, String> payload;
        try {
            payload = MAPPER.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            failJob(jobId, "Payload deserialization failed: " + e.getMessage());
            return;
        }

        VTaskContext ctx = new VTaskContextImpl(jobId, DAG_ID, jobType, tryNumber,
            payload, Map.of(), null, nodeContext);
        try {
            String result = handler.execute(ctx);
            succeedJob(jobId, result);
            log.info("[JOB-QUEUE] Job {} SUCCESS", jobId);
        } catch (Exception e) {
            log.warn("[JOB-QUEUE] Job {} failed (try {}): {}", jobId, tryNumber, e.getMessage());
            if (tryNumber < policy.maxAttempts()) {
                long delay = policy.delayForAttempt(tryNumber);
                scheduleRetry(jobId, tryNumber + 1, delay);
            } else {
                failJob(jobId, e.getMessage());
            }
        }
    }

    private void succeedJob(String jobId, String result) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_job_queue SET state = 'SUCCESS', result = ? WHERE job_id = ?")) {
            ps.setString(1, result);
            ps.setString(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[JOB-QUEUE] Failed to persist success for job {}", jobId, e);
        }
    }

    private void failJob(String jobId, String error) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE vatn_job_queue SET state = 'FAILED', error = ? WHERE job_id = ?")) {
            ps.setString(1, error);
            ps.setString(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[JOB-QUEUE] Failed to persist failure for job {}", jobId, e);
        }
    }

    private void scheduleRetry(String jobId, int nextTry, long delayMs) {
        String enqueueAfter = Instant.now().plusMillis(delayMs).toString();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_job_queue
                SET state = 'QUEUED', try_number = ?, enqueue_after = ?, error = NULL
                WHERE job_id = ?
                """)) {
            ps.setInt(1, nextTry);
            ps.setString(2, enqueueAfter);
            ps.setString(3, jobId);
            ps.executeUpdate();
            log.debug("[JOB-QUEUE] Job {} scheduled for retry {} after {}ms", jobId, nextTry, delayMs);
        } catch (SQLException e) {
            log.error("[JOB-QUEUE] Failed to schedule retry for job {}", jobId, e);
        }
    }

    private Optional<String> findByIdempotencyKey(String key) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT job_id FROM vatn_job_queue WHERE idempotency_key = ? AND state NOT IN ('FAILED','CANCELLED') LIMIT 1")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("job_id")) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private static VTaskState mapState(String s) {
        return switch (s) {
            case "QUEUED"    -> VTaskState.QUEUED;
            case "RUNNING"   -> VTaskState.RUNNING;
            case "SUCCESS"   -> VTaskState.SUCCESS;
            case "FAILED", "TTL_EXPIRED" -> VTaskState.FAILED;
            case "CANCELLED" -> VTaskState.REMOVED;
            default          -> VTaskState.NONE;
        };
    }
}
