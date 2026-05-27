package dev.vatn.core.memory;

import dev.vatn.api.VSchemaContributor;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contributes core VATN tables for node management and multi-process safety.
 */
public class VatnSchemaContributor implements VSchemaContributor {
    private static final Logger logger = LoggerFactory.getLogger(VatnSchemaContributor.class);

    @Override
    public void contribute(Statement s) throws SQLException {
        logger.info("[VATN-SCHEMA] Contributing core system tables...");

        // VATN NODES (Process and Peer tracking)
        s.execute("CREATE TABLE IF NOT EXISTS vatn_nodes (" +
            "node_id TEXT PRIMARY KEY, " +
            "kind TEXT, " +
            "pid INTEGER, " +
            "status TEXT, " +
            "host TEXT, " +
            "port INTEGER, " +
            "metadata TEXT, " +
            "started_at INTEGER)");

        // VATN RESOURCE LOCKS (Distributed locking)
        s.execute("CREATE TABLE IF NOT EXISTS vatn_resource_locks (" +
            "resource_id TEXT PRIMARY KEY, " +
            "owner_id TEXT, " +
            "acquired_at INTEGER, " +
            "expires_at INTEGER)");

        s.execute("CREATE INDEX IF NOT EXISTS idx_vatn_locks_expiry ON vatn_resource_locks(expires_at)");

        // VATN CLOCK SERVICE (Scheduled task storage for VClockService)
        s.execute("CREATE TABLE IF NOT EXISTS vatn_scheduled_tasks (" +
            "id TEXT PRIMARY KEY, " +
            "agent_id TEXT, " +
            "command TEXT, " +
            "target_time INTEGER, " +
            "status TEXT)");

        s.execute("CREATE INDEX IF NOT EXISTS idx_vatn_tasks_time ON vatn_scheduled_tasks(target_time, status)");

        logger.info("[VATN-SCHEMA] Core tables initialized.");
    }
}
