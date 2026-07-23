package dev.vatn.plugins.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PostgresPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(PostgresPlugin.class);

    private final PostgresConfig config;
    private DataSourceServiceImpl service;

    public PostgresPlugin(PostgresConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.postgres"; }
    @Override public String getName()    { return "VATN PostgreSQL Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.15-preview"; }

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
        hikari.setIdleTimeout(config.getIdleTimeoutMs());
        hikari.setMaxLifetime(config.getMaxLifetimeMs());
        hikari.setMinimumIdle(config.getMinimumIdle());
        hikari.setLeakDetectionThreshold(config.getLeakDetectionThresholdMs());
        hikari.setInitializationFailTimeout(-1);

        var dataSource = new HikariDataSource(hikari);
        service = new DataSourceServiceImpl(dataSource);
        ctx.registerService(DataSourceService.class, service);
        ctx.registerHealthCheck("postgres", () -> {
            try (var conn = dataSource.getConnection()) { return conn.isValid(1); }
            catch (java.sql.SQLException e) { return false; }
        });

        log.info("PostgreSQL DataSource initialized — url={}, pool={}",
                config.jdbcUrl(), config.getPoolSize());
    }

    @Override
    public void onShutdown() {
        if (service != null) {
            service.close();
            log.info("PostgreSQL connection pool closed.");
        }
    }
}
