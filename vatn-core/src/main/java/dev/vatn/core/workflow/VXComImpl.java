package dev.vatn.core.workflow;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.VXCom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/**
 * SQLite-backed implementation of {@link VXCom}, scoped to a single DAG run.
 */
public class VXComImpl implements VXCom {
    private static final Logger logger = LoggerFactory.getLogger(VXComImpl.class);

    private final String runId;
    private final VPersistenceService db;

    public VXComImpl(String runId, VPersistenceService db) {
        this.runId = runId;
        this.db = db;
    }

    @Override
    public void push(String taskId, String key, String value) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO vatn_xcom(run_id, task_id, xcom_key, value)
                VALUES(?, ?, ?, ?)
                """)) {
            ps.setString(1, runId);
            ps.setString(2, taskId);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[XCOM] Failed to push key={} task={} run={}", key, taskId, runId, e);
        }
    }

    @Override
    public Optional<String> pull(String taskId, String key) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT value FROM vatn_xcom WHERE run_id=? AND task_id=? AND xcom_key=?
                """)) {
            ps.setString(1, runId);
            ps.setString(2, taskId);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString("value"));
            }
        } catch (Exception e) {
            logger.error("[XCOM] Failed to pull key={} task={} run={}", key, taskId, runId, e);
        }
        return Optional.empty();
    }
}
