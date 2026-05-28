package dev.vatn.core.messaging;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.VClaimOptions;
import dev.vatn.api.workflow.VNamedQueue;
import dev.vatn.api.workflow.VQueueJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class VNamedQueueImpl implements VNamedQueue {
    private static final Logger log = LoggerFactory.getLogger(VNamedQueueImpl.class);

    private final String name;
    private final VPersistenceService db;
    private final VClaimOptions defaultOptions;

    VNamedQueueImpl(String name, VPersistenceService db, VClaimOptions defaultOptions) {
        this.name = name;
        this.db = db;
        this.defaultOptions = defaultOptions;
    }

    @Override
    public String name() { return name; }

    // ── Enqueue ───────────────────────────────────────────────────────────────

    @Override
    public String enqueue(String payload) {
        return insertNewJob(null, payload, 0, Instant.now());
    }

    @Override
    public String enqueue(String payload, int priority) {
        return insertNewJob(null, payload, priority, Instant.now());
    }

    @Override
    public String enqueueAt(String payload, Instant runAt) {
        return insertNewJob(null, payload, 0, runAt);
    }

    @Override
    public String enqueueAt(String payload, Instant runAt, int priority) {
        return insertNewJob(null, payload, priority, runAt);
    }

    @Override
    public String enqueue(String payload, Connection tx) {
        try {
            return doInsert(tx, payload, 0, Instant.now());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to enqueue job on queue " + name, e);
        }
    }

    private String insertNewJob(Connection txOpt, String payload, int priority, Instant runAt) {
        if (txOpt != null) {
            try {
                return doInsert(txOpt, payload, priority, runAt);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to enqueue job on queue " + name, e);
            }
        }
        try (Connection conn = db.getConnection()) {
            return doInsert(conn, payload, priority, runAt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to enqueue job on queue " + name, e);
        }
    }

    private String doInsert(Connection conn, String payload, int priority, Instant runAt) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO vatn_named_queue_jobs
              (id, queue, payload, priority, state, attempts, max_attempts, backoff_ms, dlq_name, run_at)
            VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
            """)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, payload);
            ps.setInt(4, priority);
            ps.setInt(5, defaultOptions.maxAttempts());
            ps.setLong(6, defaultOptions.backoff().toMillis());
            ps.setString(7, defaultOptions.deadLetterQueue());
            ps.setString(8, runAt.toString());
            ps.executeUpdate();
        }
        log.debug("[VQ-{}] Enqueued job {}", name, id);
        return id;
    }

    // ── Consume ───────────────────────────────────────────────────────────────

    @Override
    public void consume(String workerId, JobConsumer handler) {
        consume(workerId, handler, defaultOptions);
    }

    @Override
    public void consume(String workerId, JobConsumer handler, VClaimOptions options) {
        Thread.ofVirtual().name("vatn-queue-" + name + "-" + workerId)
            .start(() -> consumeLoop(workerId, handler, options));
    }

    private void consumeLoop(String workerId, JobConsumer handler, VClaimOptions options) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<VQueueJob> jobs = claimBatch(workerId, options.batchSize(), options);
                if (jobs.isEmpty()) {
                    Thread.sleep(options.pollIntervalMs());
                    continue;
                }
                for (VQueueJob job : jobs) {
                    try {
                        handler.accept(job);
                        ack(job.id(), workerId);
                    } catch (Exception e) {
                        nack(job.id(), workerId, e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[VQ-{}] Consumer loop error: {}", name, e.getMessage());
                try { Thread.sleep(options.pollIntervalMs()); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    // ── Claim ─────────────────────────────────────────────────────────────────

    @Override
    public List<VQueueJob> claimBatch(String workerId, int maxJobs) {
        return claimBatch(workerId, maxJobs, defaultOptions);
    }

    @Override
    public List<VQueueJob> claimBatch(String workerId, int maxJobs, VClaimOptions options) {
        String now = Instant.now().toString();
        String expiresAt = Instant.now().plus(options.visibilityTimeout()).toString();
        List<String> candidates = new ArrayList<>();

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id FROM vatn_named_queue_jobs
                    WHERE queue=? AND state='PENDING' AND run_at <= ?
                    ORDER BY priority DESC, created_at ASC
                    LIMIT ?
                    """)) {
                    ps.setString(1, name);
                    ps.setString(2, now);
                    ps.setInt(3, maxJobs);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) candidates.add(rs.getString(1));
                    }
                }

                if (candidates.isEmpty()) {
                    conn.rollback();
                    return List.of();
                }

                List<String> claimed = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE vatn_named_queue_jobs
                    SET state='CLAIMED', worker_id=?, claim_expires_at=?, attempts=attempts+1
                    WHERE id=? AND state='PENDING'
                    """)) {
                    for (String id : candidates) {
                        ps.setString(1, workerId);
                        ps.setString(2, expiresAt);
                        ps.setString(3, id);
                        if (ps.executeUpdate() == 1) claimed.add(id);
                        ps.clearParameters();
                    }
                }

                conn.commit();
                return claimed.isEmpty() ? List.of() : fetchJobs(claimed);
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            }
        } catch (SQLException e) {
            log.error("[VQ-{}] claimBatch failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    private List<VQueueJob> fetchJobs(List<String> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM vatn_named_queue_jobs WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                List<VQueueJob> jobs = new ArrayList<>();
                while (rs.next()) jobs.add(mapRow(rs));
                return jobs;
            }
        } catch (SQLException e) {
            log.error("[VQ-{}] fetchJobs failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    // ── Ack / Nack ────────────────────────────────────────────────────────────

    @Override
    public boolean ack(String jobId, String workerId) {
        return ack(jobId, workerId, null);
    }

    @Override
    public boolean ack(String jobId, String workerId, String result) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_named_queue_jobs
                SET state='DONE', result=?, worker_id=NULL, claim_expires_at=NULL
                WHERE id=? AND worker_id=? AND state='CLAIMED'
                """)) {
            ps.setString(1, result);
            ps.setString(2, jobId);
            ps.setString(3, workerId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("[VQ-{}] ack failed for {}: {}", name, jobId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean nack(String jobId, String workerId, String error) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int attempts = 0;
                int maxAttempts = defaultOptions.maxAttempts();
                long backoffMs = defaultOptions.backoff().toMillis();
                String dlq = defaultOptions.deadLetterQueue();
                String payload = null;

                try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT attempts, max_attempts, backoff_ms, dlq_name, payload
                    FROM vatn_named_queue_jobs WHERE id=? AND worker_id=? AND state='CLAIMED'
                    """)) {
                    ps.setString(1, jobId);
                    ps.setString(2, workerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return false; }
                        attempts   = rs.getInt("attempts");
                        maxAttempts = rs.getInt("max_attempts");
                        backoffMs  = rs.getLong("backoff_ms");
                        String rowDlq = rs.getString("dlq_name");
                        if (rowDlq != null) dlq = rowDlq;
                        payload = rs.getString("payload");
                    }
                }

                if (attempts >= maxAttempts) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE vatn_named_queue_jobs
                        SET state='DEAD', error=?, worker_id=NULL, claim_expires_at=NULL
                        WHERE id=?
                        """)) {
                        ps.setString(1, error);
                        ps.setString(2, jobId);
                        ps.executeUpdate();
                    }
                    if (dlq != null && payload != null) {
                        insertDlqJob(conn, dlq, payload);
                    }
                } else {
                    String runAt = Instant.now().plusMillis(backoffMs).toString();
                    try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE vatn_named_queue_jobs
                        SET state='PENDING', error=?, worker_id=NULL, claim_expires_at=NULL, run_at=?
                        WHERE id=?
                        """)) {
                        ps.setString(1, error);
                        ps.setString(2, runAt);
                        ps.setString(3, jobId);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw e;
            }
        } catch (SQLException e) {
            log.error("[VQ-{}] nack failed for {}: {}", name, jobId, e.getMessage());
            return false;
        }
    }

    private void insertDlqJob(Connection conn, String dlqName, String payload) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO vatn_named_queue_jobs (id, queue, payload, state, run_at)
            VALUES (?, ?, ?, 'PENDING', strftime('%Y-%m-%dT%H:%M:%SZ','now'))
            """)) {
            ps.setString(1, id);
            ps.setString(2, dlqName);
            ps.setString(3, payload);
            ps.executeUpdate();
        }
    }

    // ── Query / management ────────────────────────────────────────────────────

    @Override
    public Optional<VQueueJob> getJob(String jobId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM vatn_named_queue_jobs WHERE id=?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("[VQ-{}] getJob failed: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean cancel(String jobId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_named_queue_jobs SET state='DEAD'
                WHERE id=? AND queue=? AND state='PENDING'
                """)) {
            ps.setString(1, jobId);
            ps.setString(2, name);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("[VQ-{}] cancel failed: {}", name, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<String> waitResult(String jobId, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long sleepMs = 50;
        while (System.currentTimeMillis() < deadline) {
            Optional<VQueueJob> job = getJob(jobId);
            if (job.isEmpty()) return Optional.empty();
            if (job.get().state() == VQueueJob.State.DONE) {
                return Optional.ofNullable(job.get().result());
            }
            try { Thread.sleep(sleepMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            sleepMs = Math.min(sleepMs * 2, 500);
        }
        return Optional.empty();
    }

    @Override
    public List<VQueueJob> listDeadLetters() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT * FROM vatn_named_queue_jobs
                WHERE queue=? AND state='DEAD'
                ORDER BY created_at DESC
                """)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                List<VQueueJob> jobs = new ArrayList<>();
                while (rs.next()) jobs.add(mapRow(rs));
                return jobs;
            }
        } catch (SQLException e) {
            log.error("[VQ-{}] listDeadLetters failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    @Override
    public int purge(Duration olderThan) {
        String threshold = Instant.now().minus(olderThan).toString();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM vatn_named_queue_jobs
                WHERE queue=? AND state IN ('DONE','DEAD') AND created_at < ?
                """)) {
            ps.setString(1, name);
            ps.setString(2, threshold);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[VQ-{}] purge failed: {}", name, e.getMessage());
            return 0;
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private static VQueueJob mapRow(ResultSet rs) throws SQLException {
        VQueueJob.State state = switch (rs.getString("state")) {
            case "CLAIMED" -> VQueueJob.State.CLAIMED;
            case "DONE"    -> VQueueJob.State.DONE;
            case "FAILED"  -> VQueueJob.State.FAILED;
            case "DEAD"    -> VQueueJob.State.DEAD;
            default        -> VQueueJob.State.PENDING;
        };
        return new VQueueJob(
            rs.getString("id"),
            rs.getString("queue"),
            rs.getString("payload"),
            rs.getInt("priority"),
            parseInstant(rs.getString("run_at")),
            rs.getInt("attempts"),
            state,
            rs.getString("worker_id"),
            parseInstant(rs.getString("claim_expires_at")),
            rs.getString("error"),
            rs.getString("result"),
            parseInstant(rs.getString("created_at"))
        );
    }

    private static Instant parseInstant(String s) {
        return s != null ? Instant.parse(s) : null;
    }
}
