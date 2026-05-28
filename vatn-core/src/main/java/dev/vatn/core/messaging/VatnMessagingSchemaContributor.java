package dev.vatn.core.messaging;

import dev.vatn.api.VSchemaContributor;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the VATN messaging tables: named queue jobs, durable topic events, and per-consumer offsets.
 */
public class VatnMessagingSchemaContributor implements VSchemaContributor {

    @Override
    public void contribute(Statement s) throws SQLException {
        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_named_queue_jobs (
                id               TEXT    PRIMARY KEY,
                queue            TEXT    NOT NULL,
                payload          TEXT    NOT NULL DEFAULT '{}',
                priority         INTEGER NOT NULL DEFAULT 0,
                state            TEXT    NOT NULL DEFAULT 'PENDING',
                attempts         INTEGER NOT NULL DEFAULT 0,
                max_attempts     INTEGER NOT NULL DEFAULT 3,
                worker_id        TEXT,
                claim_expires_at TEXT,
                backoff_ms       INTEGER NOT NULL DEFAULT 30000,
                dlq_name         TEXT,
                run_at           TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
                error            TEXT,
                result           TEXT,
                created_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
            )
            """);

        s.execute("CREATE INDEX IF NOT EXISTS idx_vnqj_queue_state ON vatn_named_queue_jobs(queue, state, run_at, priority)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_vnqj_claim_expiry ON vatn_named_queue_jobs(state, claim_expires_at)");

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_topic_events (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                topic        TEXT    NOT NULL,
                payload      TEXT    NOT NULL,
                published_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
            )
            """);

        s.execute("CREATE INDEX IF NOT EXISTS idx_vte_topic_id ON vatn_topic_events(topic, id)");

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_topic_offsets (
                topic       TEXT    NOT NULL,
                consumer_id TEXT    NOT NULL,
                offset      INTEGER NOT NULL DEFAULT 0,
                updated_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
                PRIMARY KEY (topic, consumer_id)
            )
            """);
    }
}
