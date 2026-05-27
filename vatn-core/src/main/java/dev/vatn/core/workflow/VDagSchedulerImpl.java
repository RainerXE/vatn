package dev.vatn.core.workflow;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.VDag;
import dev.vatn.api.workflow.VDagEngine;
import dev.vatn.api.workflow.VDagScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cron-based DAG scheduler.
 *
 * <p>Polls every minute, checks which DAGs have a cron expression whose next fire time
 * has passed, and triggers them via {@link VDagEngine}. Schedules from {@link VDag#schedule()}
 * are automatically registered when a DAG is loaded via {@link #registerDagSchedules()}.
 *
 * <p>Cron format: standard 5-field UNIX cron (minute hour day month weekday).
 * Supported field syntax: {@code *}, numbers, ranges ({@code 1-5}),
 * step values ({@code * /5}), and comma-separated lists ({@code 1,3,5}).
 */
public class VDagSchedulerImpl implements VDagScheduler {
    private static final Logger logger = LoggerFactory.getLogger(VDagSchedulerImpl.class);
    private static final long POLL_INTERVAL_MS = 30_000;

    private final VDagRegistryImpl registry;
    private final VDagEngine engine;
    private final VPersistenceService db;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Future<?> schedulerFuture;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vatn-dag-scheduler");
                t.setDaemon(true);
                return t;
            });

    public VDagSchedulerImpl(VDagRegistryImpl registry, VDagEngine engine, VPersistenceService db) {
        this.registry = registry;
        this.engine = engine;
        this.db = db;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        schedulerFuture = scheduler.scheduleAtFixedRate(this::tick, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("[DAG-SCHEDULER] Started");
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (schedulerFuture != null) schedulerFuture.cancel(false);
            scheduler.shutdownNow();
            logger.info("[DAG-SCHEDULER] Stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void schedule(String dagId, String cronExpression) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO vatn_dag_schedule(dag_id, cron_expr, active)
                VALUES(?, ?, 1)
                """)) {
            ps.setString(1, dagId);
            ps.setString(2, cronExpression);
            ps.executeUpdate();
            logger.info("[DAG-SCHEDULER] Registered schedule '{}' for DAG {}", cronExpression, dagId);
        } catch (Exception e) {
            logger.error("[DAG-SCHEDULER] Failed to register schedule for DAG {}", dagId, e);
        }
    }

    @Override
    public void unschedule(String dagId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE vatn_dag_schedule SET active=0 WHERE dag_id=?")) {
            ps.setString(1, dagId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[DAG-SCHEDULER] Failed to unschedule DAG {}", dagId, e);
        }
    }

    @Override
    public List<String> listScheduled() {
        List<String> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT dag_id FROM vatn_dag_schedule WHERE active=1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(rs.getString("dag_id"));
        } catch (Exception e) {
            logger.error("[DAG-SCHEDULER] listScheduled failed", e);
        }
        return result;
    }

    /**
     * Registers cron schedules declared in {@link VDag#schedule()} for all DAGs in the registry.
     * Call after all DAGs are registered and before {@link #start()}.
     */
    public void registerDagSchedules() {
        for (VDag dag : registry.listDags()) {
            if (dag.schedule() != null && !dag.schedule().isBlank()) {
                schedule(dag.id(), dag.schedule());
            }
        }
    }

    private void tick() {
        try {
            LocalDateTime now = LocalDateTime.now();
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT dag_id, cron_expr, last_run_at FROM vatn_dag_schedule WHERE active=1");
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String dagId = rs.getString("dag_id");
                    String cron = rs.getString("cron_expr");
                    String lastRunAt = rs.getString("last_run_at");

                    if (registry.isPaused(dagId)) continue;

                    LocalDateTime lastRun = lastRunAt != null ? LocalDateTime.parse(lastRunAt) : null;
                    LocalDateTime nextFire = nextFireTime(cron, lastRun != null ? lastRun : now.minusMinutes(2));

                    if (nextFire != null && !now.isBefore(nextFire)) {
                        try {
                            engine.trigger(dagId, Map.of(), false);
                            updateLastRunAt(dagId, now);
                            logger.info("[DAG-SCHEDULER] Triggered DAG {} (cron: {})", dagId, cron);
                        } catch (Exception e) {
                            logger.error("[DAG-SCHEDULER] Failed to trigger DAG {}", dagId, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[DAG-SCHEDULER] Tick failed", e);
        }
    }

    private void updateLastRunAt(String dagId, LocalDateTime when) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE vatn_dag_schedule SET last_run_at=? WHERE dag_id=?")) {
            ps.setString(1, when.toString());
            ps.setString(2, dagId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("[DAG-SCHEDULER] Failed to update last_run_at for {}", dagId, e);
        }
    }

    // ─────────────────────────────────────── minimal 5-field cron evaluator ──

    /**
     * Returns the next fire time after {@code after} for the given 5-field cron expression.
     * Returns null if the expression cannot be parsed.
     */
    static LocalDateTime nextFireTime(String cron, LocalDateTime after) {
        if (cron == null || cron.isBlank()) return null;
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5) {
            LoggerFactory.getLogger(VDagSchedulerImpl.class)
                    .warn("[DAG-SCHEDULER] Invalid cron '{}' — expected 5 fields", cron);
            return null;
        }
        // Advance to the next whole minute (cron resolution is 1 minute)
        LocalDateTime candidate = after.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
        // Search up to 366 days to avoid infinite loop on impossible expressions
        for (int i = 0; i < 366 * 24 * 60; i++) {
            if (cronMatches(fields, candidate)) return candidate;
            candidate = candidate.plusMinutes(1);
        }
        return null;
    }

    private static boolean cronMatches(String[] fields, LocalDateTime dt) {
        // fields: [minute, hour, dayOfMonth, month, dayOfWeek]
        return fieldMatches(fields[0], dt.getMinute(), 0, 59)
                && fieldMatches(fields[1], dt.getHour(), 0, 23)
                && fieldMatches(fields[2], dt.getDayOfMonth(), 1, 31)
                && fieldMatches(fields[3], dt.getMonthValue(), 1, 12)
                && fieldMatches(fields[4], dt.getDayOfWeek().getValue() % 7, 0, 6); // 0=Sun
    }

    private static boolean fieldMatches(String field, int value, int min, int max) {
        if ("*".equals(field)) return true;
        for (String part : field.split(",")) {
            if (part.contains("/")) {
                String[] stepParts = part.split("/");
                int step = Integer.parseInt(stepParts[1]);
                int start = "*".equals(stepParts[0]) ? min : Integer.parseInt(stepParts[0]);
                if (value >= start && (value - start) % step == 0) return true;
            } else if (part.contains("-")) {
                String[] rangeParts = part.split("-");
                int lo = Integer.parseInt(rangeParts[0]);
                int hi = Integer.parseInt(rangeParts[1]);
                if (value >= lo && value <= hi) return true;
            } else {
                if (Integer.parseInt(part) == value) return true;
            }
        }
        return false;
    }
}
