package dev.vatn.api;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Interface for plugins to contribute their own schema (tables, indices) to the node's database.
 */
@VatnApi(since = "1.0")
@FunctionalInterface
public interface VSchemaContributor {
    
    /**
     * Called during node bootstrap or service registration.
     * Implementation should execute "CREATE TABLE IF NOT EXISTS" statements.
     */
    void contribute(Statement statement) throws SQLException;
}
