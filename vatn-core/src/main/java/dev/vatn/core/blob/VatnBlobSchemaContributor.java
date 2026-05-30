package dev.vatn.core.blob;

import dev.vatn.api.VSchemaContributor;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the metadata table for {@link LocalBlobStore}. Blob bytes live on disk
 * (content-addressed); this table tracks keys, their content hash, size, pin state, and access
 * times for LRU eviction.
 */
public class VatnBlobSchemaContributor implements VSchemaContributor {

    @Override
    public void contribute(Statement s) throws SQLException {
        s.execute("""
            CREATE TABLE IF NOT EXISTS vatn_blobs (
                key              TEXT    PRIMARY KEY,
                content_hash     TEXT    NOT NULL,
                size             INTEGER NOT NULL,
                content_type     TEXT,
                pinned           INTEGER NOT NULL DEFAULT 0,
                created_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
                last_accessed_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
            )
            """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_vatn_blobs_hash    ON vatn_blobs(content_hash)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_vatn_blobs_lru     ON vatn_blobs(pinned, last_accessed_at)");
    }
}
