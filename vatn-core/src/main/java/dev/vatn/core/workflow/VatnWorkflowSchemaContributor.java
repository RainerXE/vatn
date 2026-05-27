package dev.vatn.core.workflow;

import dev.vatn.api.VSchemaContributor;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the VATN workflow tables in the node's SQLite database.
 * All table names carry the {@code vatn_} prefix — no application-specific tables here.
 */
public class VatnWorkflowSchemaContributor implements VSchemaContributor {

    @Override
    public void contribute(Statement s) throws SQLException {
        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_dag (
                dag_id        TEXT PRIMARY KEY,
                description   TEXT,
                schedule      TEXT,
                max_active_runs INTEGER NOT NULL DEFAULT 1,
                catch_up      INTEGER NOT NULL DEFAULT 0,
                sla_seconds   INTEGER NOT NULL DEFAULT 0,
                tags          TEXT    NOT NULL DEFAULT '[]',
                definition    TEXT    NOT NULL DEFAULT '{}',
                paused        INTEGER NOT NULL DEFAULT 0,
                created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
            )
            """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_dag_run (
                run_id          TEXT PRIMARY KEY,
                dag_id          TEXT NOT NULL,
                state           TEXT NOT NULL DEFAULT 'QUEUED',
                logical_date    TEXT,
                start_date      TEXT,
                end_date        TEXT,
                external_trigger INTEGER NOT NULL DEFAULT 1,
                conf            TEXT    NOT NULL DEFAULT '{}',
                created_at      TEXT    NOT NULL DEFAULT (datetime('now'))
            )
            """);

        s.execute("CREATE INDEX IF NOT EXISTS idx_dag_run_dag_id ON vatn_dag_run(dag_id)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_dag_run_state   ON vatn_dag_run(state)");

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_task_instance (
                task_id    TEXT NOT NULL,
                run_id     TEXT NOT NULL,
                dag_id     TEXT NOT NULL,
                state      TEXT NOT NULL DEFAULT 'NONE',
                try_number INTEGER NOT NULL DEFAULT 1,
                start_date TEXT,
                end_date   TEXT,
                hostname   TEXT,
                PRIMARY KEY (task_id, run_id)
            )
            """);

        s.execute("CREATE INDEX IF NOT EXISTS idx_ti_run_id ON vatn_task_instance(run_id)");

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_xcom (
                run_id     TEXT NOT NULL,
                task_id    TEXT NOT NULL,
                xcom_key   TEXT NOT NULL,
                value      TEXT,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                PRIMARY KEY (run_id, task_id, xcom_key)
            )
            """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_pool (
                pool_name   TEXT PRIMARY KEY,
                slots       INTEGER NOT NULL,
                description TEXT    NOT NULL DEFAULT '',
                used_slots  INTEGER NOT NULL DEFAULT 0
            )
            """);

        s.execute("""
            INSERT OR IGNORE INTO vatn_pool(pool_name, slots, description)
            VALUES('default_pool', 128, 'Built-in default concurrency pool')
            """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_dag_schedule (
                dag_id     TEXT PRIMARY KEY,
                cron_expr  TEXT NOT NULL,
                last_run_at TEXT,
                next_run_at TEXT,
                active     INTEGER NOT NULL DEFAULT 1
            )
            """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_job_queue (
                job_id            TEXT PRIMARY KEY,
                job_type          TEXT NOT NULL,
                payload           TEXT NOT NULL DEFAULT '{}',
                state             TEXT NOT NULL DEFAULT 'QUEUED',
                try_number        INTEGER NOT NULL DEFAULT 1,
                max_attempts      INTEGER NOT NULL DEFAULT 1,
                initial_delay_ms  INTEGER NOT NULL DEFAULT 0,
                backoff_multiplier REAL   NOT NULL DEFAULT 1.0,
                max_delay_ms      INTEGER NOT NULL DEFAULT 0,
                result            TEXT,
                error             TEXT,
                idempotency_key   TEXT,
                enqueue_after     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
                expires_at        TEXT,
                created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
            )
            """);

        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_job_idempotency ON vatn_job_queue(idempotency_key) WHERE idempotency_key IS NOT NULL");
        s.execute("CREATE INDEX IF NOT EXISTS idx_job_state ON vatn_job_queue(state, enqueue_after)");

        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_event_log (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id      TEXT    NOT NULL,
                dag_id      TEXT    NOT NULL,
                task_id     TEXT,
                event_type  TEXT    NOT NULL,
                payload     TEXT,
                occurred_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
            )
            """);

        s.execute("CREATE INDEX IF NOT EXISTS idx_event_log_run ON vatn_event_log(run_id, task_id, event_type)");
    }
}
