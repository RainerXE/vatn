package dev.vatn.api;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Universal SPI for persistent storage.
 * Decouples the node's database implementation (SQLite, Postgres, H2) from the plugins.
 */
@VatnApi(since = "1.0")
public interface VPersistenceService extends VService {
    
    /**
     * Retrieves a connection to the node's database.
     */
    Connection getConnection() throws SQLException;

    /**
     * Registers a schema contributor to participate in database initialization.
     * Should be called during the plugin's onInitialize phase.
     */
    void registerSchemaContributor(VSchemaContributor contributor);
}
