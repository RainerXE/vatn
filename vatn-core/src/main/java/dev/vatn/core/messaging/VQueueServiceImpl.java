package dev.vatn.core.messaging;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.VClaimOptions;
import dev.vatn.api.workflow.VNamedQueue;
import dev.vatn.api.workflow.VQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class VQueueServiceImpl implements VQueueService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(VQueueServiceImpl.class);

    private final VPersistenceService db;
    private final ConcurrentHashMap<String, VNamedQueueImpl> queues = new ConcurrentHashMap<>();
    private final Thread sweeper;
    private volatile boolean running = true;

    public VQueueServiceImpl(VPersistenceService db) {
        this.db = db;
        sweeper = Thread.ofVirtual().name("vatn-queue-sweeper").start(this::sweepLoop);
    }

    /** Stops the background sweeper thread. Idempotent. */
    @Override
    public void close() {
        running = false;
        sweeper.interrupt();
    }

    @Override
    public VNamedQueue queue(String name) {
        return queues.computeIfAbsent(name, n -> new VNamedQueueImpl(n, db, VClaimOptions.defaults()));
    }

    @Override
    public VNamedQueue queue(String name, VClaimOptions defaultOptions) {
        return queues.computeIfAbsent(name, n -> new VNamedQueueImpl(n, db, defaultOptions));
    }

    // Reclaim jobs whose visibility timeout has expired — puts them back to PENDING
    private void sweepLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(5_000);
                if (!running) break;
                reclaimExpired();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[VQ-SWEEPER] Error: {}", e.getMessage());
            }
        }
    }

    private void reclaimExpired() {
        String now = Instant.now().toString();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_named_queue_jobs
                SET state='PENDING', worker_id=NULL, claim_expires_at=NULL
                WHERE state='CLAIMED' AND claim_expires_at IS NOT NULL AND claim_expires_at < ?
                """)) {
            ps.setString(1, now);
            int reclaimed = ps.executeUpdate();
            if (reclaimed > 0) {
                log.debug("[VQ-SWEEPER] Reclaimed {} expired jobs", reclaimed);
            }
        } catch (SQLException e) {
            log.warn("[VQ-SWEEPER] Reclaim failed: {}", e.getMessage());
        }
    }
}
