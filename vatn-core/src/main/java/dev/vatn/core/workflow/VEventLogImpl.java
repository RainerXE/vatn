package dev.vatn.core.workflow;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.VEventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed, append-only event log for DAG task transitions.
 */
public class VEventLogImpl implements VEventLog {

    private static final Logger log = LoggerFactory.getLogger(VEventLogImpl.class);

    private final VPersistenceService db;

    public VEventLogImpl(VPersistenceService db) {
        this.db = db;
    }

    @Override
    public void append(String runId, String dagId, String taskId, String eventType, String payload) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO vatn_event_log(run_id, dag_id, task_id, event_type, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, runId);
            ps.setString(2, dagId);
            ps.setString(3, taskId);
            ps.setString(4, eventType);
            ps.setString(5, payload);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[EVENT-LOG] Failed to append event {}/{}/{}", runId, taskId, eventType, e);
        }
    }

    @Override
    public boolean hasSucceeded(String runId, String taskId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM vatn_event_log
                WHERE run_id = ? AND task_id = ? AND event_type = 'TASK_SUCCESS'
                LIMIT 1
                """)) {
            ps.setString(1, runId);
            ps.setString(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.error("[EVENT-LOG] hasSucceeded query failed for {}/{}", runId, taskId, e);
            return false;
        }
    }

    @Override
    public List<String> getInterruptedRunIds() {
        List<String> runIds = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT run_id FROM vatn_event_log
                WHERE event_type = 'DAG_TRIGGERED'
                  AND run_id NOT IN (
                    SELECT run_id FROM vatn_event_log
                    WHERE event_type IN ('DAG_SUCCESS', 'DAG_FAILED')
                  )
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) runIds.add(rs.getString("run_id"));
            }
        } catch (Exception e) {
            log.error("[EVENT-LOG] getInterruptedRunIds failed", e);
        }
        return runIds;
    }
}
