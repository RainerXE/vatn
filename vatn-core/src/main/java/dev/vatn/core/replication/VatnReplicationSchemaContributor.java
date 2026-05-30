package dev.vatn.core.replication;

import dev.vatn.api.VSchemaContributor;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tables for {@link VReplicationServiceImpl}: the materialised set data, the append-only change
 * feed, and per-peer watermarks.
 */
public class VatnReplicationSchemaContributor implements VSchemaContributor {

    @Override
    public void contribute(Statement s) throws SQLException {
        // Materialised current state per (set, key).
        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_repl_data (
                set_name    TEXT    NOT NULL,
                key         TEXT    NOT NULL,
                value       BLOB,
                version     INTEGER NOT NULL,
                origin_node TEXT    NOT NULL,
                updated_at  TEXT    NOT NULL,
                tombstone   INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (set_name, key)
            )
            """);

        // Append-only change feed; seq is the per-node replication offset used for watermarks.
        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_repl_feed (
                seq         INTEGER PRIMARY KEY AUTOINCREMENT,
                set_name    TEXT    NOT NULL,
                key         TEXT    NOT NULL,
                value       BLOB,
                version     INTEGER NOT NULL,
                origin_node TEXT    NOT NULL,
                updated_at  TEXT    NOT NULL,
                tombstone   INTEGER NOT NULL DEFAULT 0
            )
            """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_vatn_repl_feed_set ON vatn_repl_feed(set_name, seq)");

        // Per-peer watermark. peer_node is the source node id for inbound pulls,
        // or "out:<nodeId>" for outbound push progress.
        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_repl_watermark (
                set_name  TEXT    NOT NULL,
                peer_node TEXT    NOT NULL,
                watermark INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (set_name, peer_node)
            )
            """);
    }
}
