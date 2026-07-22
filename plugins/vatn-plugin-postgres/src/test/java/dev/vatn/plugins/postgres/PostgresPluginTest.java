package dev.vatn.plugins.postgres;

import dev.vatn.core.VNodeContextImpl;
import dev.vatn.core.security.VFirewallImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class PostgresPluginTest {

    // ---- PostgresConfig builder tests ----

    @Test
    void configWithDefaults() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "user", "secret");
        assertEquals("localhost", cfg.getHost());
        assertEquals(5432, cfg.getPort());
        assertEquals("mydb", cfg.getDatabase());
        assertEquals("user", cfg.getUsername());
        assertEquals("secret", cfg.getPassword());
    }

    @Test
    void configWithPoolSize() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withPoolSize(20);
        assertEquals(20, cfg.getPoolSize());
    }

    @Test
    void configWithIdleTimeoutMs() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withIdleTimeoutMs(300_000);
        assertEquals(300_000, cfg.getIdleTimeoutMs());
    }

    @Test
    void configWithMaxLifetimeMs() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withMaxLifetimeMs(3_600_000);
        assertEquals(3_600_000, cfg.getMaxLifetimeMs());
    }

    @Test
    void configWithMinimumIdle() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withMinimumIdle(5);
        assertEquals(5, cfg.getMinimumIdle());
    }

    @Test
    void configMinimumIdleDefaultsToPoolSize() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withPoolSize(20);
        assertEquals(20, cfg.getPoolSize());
        assertEquals(20, cfg.getMinimumIdle());
    }

    @Test
    void configWithLeakDetectionThresholdMs() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withLeakDetectionThresholdMs(10_000);
        assertEquals(10_000, cfg.getLeakDetectionThresholdMs());
    }

    @Test
    void configJdbcUrl() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p");
        assertEquals("jdbc:postgresql://localhost:5432/mydb", cfg.jdbcUrl());
    }

    @Test
    void configJdbcUrlCustomHostPort() {
        var cfg = PostgresConfig.of("pg.example.com", 15432, "analytics", "u", "p");
        assertEquals("jdbc:postgresql://pg.example.com:15432/analytics", cfg.jdbcUrl());
    }

    @Test
    void configWithConnectionTimeoutMs() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withConnectionTimeoutMs(5000);
        assertEquals(5000, cfg.getConnectionTimeoutMs());
    }

    @Test
    void configWithAutoCommit() {
        var cfg = PostgresConfig.of("localhost", 5432, "mydb", "u", "p").withAutoCommit(false);
        assertFalse(cfg.isAutoCommit());
    }

    // ---- PostgresPlugin lifecycle tests ----

    @Test
    void pluginRegistersService() {
        var config = PostgresConfig.of("localhost", 5432, "testdb", "u", "p").withPoolSize(1);
        var plugin = new PostgresPlugin(config);
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        var service = ctx.getService(DataSourceService.class);
        assertTrue(service.isPresent());
        assertInstanceOf(DataSourceService.class, service.get());
    }

    @Test
    void healthCheckIsRegistered() {
        var config = PostgresConfig.of("localhost", 5432, "testdb", "u", "p").withPoolSize(1);
        var plugin = new PostgresPlugin(config);
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        var healthChecks = ctx.getHealthChecks();
        assertTrue(healthChecks.containsKey("postgres"));
    }

    @Test
    void pluginMetadata() {
        var plugin = new PostgresPlugin(PostgresConfig.of("h", 1, "d", "u", "p"));
        assertEquals("dev.vatn.plugins.postgres", plugin.getId());
        assertEquals("VATN PostgreSQL Plugin", plugin.getName());
    }

    private static VNodeContextImpl createTestContext() {
        var firewall = new VFirewallImpl();
        return new VNodeContextImpl("test-node", firewall, new HashMap<>());
    }
}
