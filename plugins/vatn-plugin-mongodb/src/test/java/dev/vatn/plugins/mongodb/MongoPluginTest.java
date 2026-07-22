package dev.vatn.plugins.mongodb;

import dev.vatn.api.VNodeContext;
import dev.vatn.core.VNodeContextImpl;
import dev.vatn.core.security.VFirewallImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MongoPluginTest {

    @Test
    void configOfCreatesWithDefaults() {
        var cfg = MongoConfig.of("mongodb://host:27017", "testdb");
        assertEquals("mongodb://host:27017", cfg.getConnectionString());
        assertEquals("testdb", cfg.getDatabase());
        assertEquals(0, cfg.getMinPoolSize());
        assertEquals(100, cfg.getMaxPoolSize());
        assertEquals(0, cfg.getMaxIdleTimeMs());
    }

    @Test
    void configLocalhostUsesDefaultUri() {
        var cfg = MongoConfig.localhost("myapp");
        assertEquals("mongodb://localhost:27017", cfg.getConnectionString());
        assertEquals("myapp", cfg.getDatabase());
    }

    @Test
    void configWithMinPoolSize() {
        var cfg = MongoConfig.of("uri", "db").withMinPoolSize(5);
        assertEquals(5, cfg.getMinPoolSize());
        assertEquals(100, cfg.getMaxPoolSize());
    }

    @Test
    void configWithMaxPoolSize() {
        var cfg = MongoConfig.of("uri", "db").withMaxPoolSize(50);
        assertEquals(50, cfg.getMaxPoolSize());
        assertEquals(0, cfg.getMinPoolSize());
    }

    @Test
    void configWithMaxIdleTimeMs() {
        var cfg = MongoConfig.of("uri", "db").withMaxIdleTimeMs(30000);
        assertEquals(30000, cfg.getMaxIdleTimeMs());
    }

    @Test
    void configBuilderChaining() {
        var cfg = MongoConfig.of("uri", "db")
                .withMinPoolSize(3)
                .withMaxPoolSize(25)
                .withMaxIdleTimeMs(15000);
        assertEquals(3, cfg.getMinPoolSize());
        assertEquals(25, cfg.getMaxPoolSize());
        assertEquals(15000, cfg.getMaxIdleTimeMs());
    }

    @Test
    void configReturnTypePreservesConnectionString() {
        var cfg = MongoConfig.of("mongodb+srv://user:pass@cluster.mongodb.net", "prod");
        var modified = cfg.withMaxPoolSize(200);
        assertEquals("mongodb+srv://user:pass@cluster.mongodb.net", modified.getConnectionString());
        assertEquals("prod", modified.getDatabase());
        assertEquals(200, modified.getMaxPoolSize());
    }

    @Test
    void pluginRegistersService() {
        var plugin = new MongoPlugin(MongoConfig.localhost("test"));
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        var service = ctx.getService(MongoService.class);
        assertTrue(service.isPresent());
    }

    @Test
    void pluginHealthCheckRegistered() {
        var plugin = new MongoPlugin(MongoConfig.localhost("test"));
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        var healthChecks = ctx.getHealthChecks();
        assertTrue(healthChecks.containsKey("mongodb"));
    }

    @Test
    void pluginMetadata() {
        var plugin = new MongoPlugin(MongoConfig.localhost("test"));
        assertEquals("dev.vatn.plugins.mongodb", plugin.getId());
        assertEquals("VATN MongoDB Plugin", plugin.getName());
        assertEquals("1.0-alpha.14-preview", plugin.getVersion());
    }

    @Test
    void pluginShutdownClosesService() {
        var plugin = new MongoPlugin(MongoConfig.localhost("test"));
        var ctx = createTestContext();
        plugin.onInitialize(ctx);
        // Should not throw
        plugin.onShutdown();
    }

    private static VNodeContextImpl createTestContext() {
        var firewall = new VFirewallImpl();
        return new VNodeContextImpl("test-node", firewall, new HashMap<>());
    }
}
