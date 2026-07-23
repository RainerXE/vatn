package dev.vatn.core.memory;
 
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.File;
import org.sqlite.SQLiteDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
 
import java.util.ArrayList;
import java.util.List;
import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VSchemaContributor;

/**
 * Context-aware Database Manager for VATN.
 * Implements VPersistenceService to provide pluggable storage to the node.
 */
public class DatabaseManager implements VPersistenceService, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final SQLiteDataSource dataSource;
    private final String url;
    private final List<VSchemaContributor> contributors = new ArrayList<>();
    private boolean initialized = false;
 
    public DatabaseManager(String jdbcUrl) {
        this.url = jdbcUrl;
        this.dataSource = new SQLiteDataSource();
        this.dataSource.setUrl(url);
        init();
    }
 
    private void init() {
        logger.info("[DB-INIT] Initializing database at: {}", url);
        
        if (url.startsWith("jdbc:sqlite:")) {
            String path = url.substring("jdbc:sqlite:".length());
            File dbFile = new File(path);
            File parent = dbFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        }

        try (Connection conn = dataSource.getConnection()) {
            Statement s = conn.createStatement();
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA temp_store=MEMORY");

            // 1. Execute Core VATN Schema
            new VatnSchemaContributor().contribute(s);

            // 2. Execute already registered contributors
            for (VSchemaContributor contributor : contributors) {
                contributor.contribute(s);
            }
            
            this.initialized = true;
            logger.info("[DB-INIT] Core database initialized.");
        } catch (SQLException e) {
            logger.error("[DB-INIT] FAILURE: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    @Override
    public void registerSchemaContributor(VSchemaContributor contributor) {
        this.contributors.add(contributor);
        if (initialized) {
            try (Connection conn = dataSource.getConnection();
                 Statement s = conn.createStatement()) {
                contributor.contribute(s);
            } catch (SQLException e) {
                logger.error("[DB-PERSISTENCE] Failed to execute late-registered contributor: {}", e.getMessage());
            }
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
 
    public String getUrl() {
        return url;
    }

    public javax.sql.DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Checkpoints and removes the SQLite WAL/SHM sidecar files, then closes
     * the data source. Safe to call multiple times. Must be called in tests
     * (via {@code @AfterEach}) so JUnit's {@code @TempDir} cleanup can delete
     * the database directory without hitting locked WAL files.
     */
    @Override
    public void close() {
        try (Connection conn = dataSource.getConnection();
             Statement s = conn.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            s.execute("PRAGMA journal_mode=DELETE");
        } catch (SQLException e) {
            logger.debug("[DB] WAL checkpoint on close failed: {}", e.getMessage());
        }
        // macOS can briefly retain WAL/SHM file locks after Connection.close(),
        // so retry deletion with a short delay if the first attempt fails.
        if (url.startsWith("jdbc:sqlite:")) {
            String path = url.substring("jdbc:sqlite:".length());
            java.nio.file.Path walPath = java.nio.file.Paths.get(path + "-wal");
            java.nio.file.Path shmPath = java.nio.file.Paths.get(path + "-shm");
            for (int attempt = 0; attempt < 5; attempt++) {
                boolean allGone = true;
                try { if (java.nio.file.Files.exists(walPath)) { java.nio.file.Files.delete(walPath); } }
                catch (java.io.IOException e) { allGone = false; }
                try { if (java.nio.file.Files.exists(shmPath)) { java.nio.file.Files.delete(shmPath); } }
                catch (java.io.IOException e) { allGone = false; }
                if (allGone) break;
                try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
}
