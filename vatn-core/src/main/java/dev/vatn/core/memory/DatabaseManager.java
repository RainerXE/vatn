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
public class DatabaseManager implements VPersistenceService {
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
}
