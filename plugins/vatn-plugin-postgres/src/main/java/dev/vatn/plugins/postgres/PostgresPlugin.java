package dev.vatn.plugins.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * PostgreSQL plugin for VATN. Registers a HikariCP-pooled {@link DataSource}
 * in the node context so any plugin or handler can borrow connections.
 *
 * <pre>{@code
 * PostgresConfig config = PostgresConfig
 *     .of("localhost", 5432, "mydb", "user", "secret")
 *     .withPoolSize(10);
 *
 * VNodeRunner.create(8080)
 *     .addPlugin(new PostgresPlugin(config))
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 *
 * // Inside any plugin
 * DataSource ds = ctx.getService(DataSourceService.class).orElseThrow().dataSource();
 * try (var conn = ds.getConnection();
 *      var ps   = conn.prepareStatement("SELECT id, name FROM users WHERE id = ?")) {
 *     ps.setInt(1, userId);
 *     var rs = ps.executeQuery();
 *     ...
 * }
 * }</pre>
 */
public class PostgresPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(PostgresPlugin.class);

    private final PostgresConfig config;
    private HikariDataSource dataSource;

    public PostgresPlugin(PostgresConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.postgres"; }
    @Override public String getName()    { return "VATN PostgreSQL Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.getUsername());
        hikari.setPassword(config.getPassword());
        hikari.setMaximumPoolSize(config.getPoolSize());
        hikari.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikari.setAutoCommit(config.isAutoCommit());
        hikari.setPoolName("vatn-postgres");

        dataSource = new HikariDataSource(hikari);
        ctx.registerService(DataSourceService.class, () -> dataSource);
        ctx.registerHealthCheck("postgres", () -> {
            try (var conn = dataSource.getConnection()) { return conn.isValid(1); }
            catch (java.sql.SQLException e) { return false; }
        });

        log.info("PostgreSQL DataSource initialized — url={}, pool={}",
                config.jdbcUrl(), config.getPoolSize());
    }

    @Override
    public void onShutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("PostgreSQL connection pool closed.");
        }
    }
}
