package dev.vatn.plugins.metrics;

import dev.vatn.api.VJson;
import dev.vatn.core.VJsonImpl;
import dev.vatn.core.VNodeContextImpl;
import dev.vatn.core.security.VFirewallImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsPluginTest {

    @Test
    void metricsServiceCreatesRegistry() {
        var service = new MetricsServiceImpl(MetricsConfig.defaults());
        assertNotNull(service.registry());
    }

    @Test
    void metricsServiceScrapeReturnsEmptyString() {
        var service = new MetricsServiceImpl(MetricsConfig.defaults());
        assertEquals("", service.scrape());
    }

    @Test
    void metricsServiceRegistryReturnsSameInstance() {
        var service = new MetricsServiceImpl(MetricsConfig.defaults());
        assertSame(service.registry(), service.registry());
    }

    @Test
    void metricsServiceAppliesGlobalTags() {
        var config = MetricsConfig.defaults()
                .withTag("env", "test")
                .withTag("service", "vatn");
        var service = new MetricsServiceImpl(config);
        var counter = service.registry().counter("test.counter", "local", "val");
        var tags = counter.getId().getTags();
        assertTrue(tags.stream().anyMatch(t -> "env".equals(t.getKey()) && "test".equals(t.getValue())));
        assertTrue(tags.stream().anyMatch(t -> "service".equals(t.getKey()) && "vatn".equals(t.getValue())));
        assertTrue(tags.stream().anyMatch(t -> "local".equals(t.getKey()) && "val".equals(t.getValue())));
    }

    @Test
    void pluginInitializesCorrectly() {
        var plugin = new MetricsPlugin();
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        assertTrue(ctx.getService(MetricsService.class).isPresent());
        assertEquals(1, ctx.getRegisteredRoutes().size());
        assertEquals("/metrics", ctx.getRegisteredRoutes().get(0));
    }

    @Test
    void healthCheckRegisteredAndReturnsTrue() {
        var plugin = new MetricsPlugin();
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        var healthChecks = ctx.getHealthChecks();
        assertTrue(healthChecks.containsKey("metrics"));
        assertTrue(healthChecks.get("metrics").get());
    }

    @Test
    void pluginMetadataUnchanged() {
        var plugin = new MetricsPlugin();
        assertEquals("dev.vatn.plugins.metrics", plugin.getId());
        assertEquals("VATN Metrics Plugin", plugin.getName());
    }

    @Test
    void pluginWithCustomPath() {
        var config = MetricsConfig.defaults().withPath("/internal/metrics");
        var plugin = new MetricsPlugin(config);
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        assertEquals("/internal/metrics", ctx.getRegisteredRoutes().get(0));
    }

    @Test
    void pluginWithoutJvmMetrics() {
        var config = MetricsConfig.defaults().withoutJvmMetrics();
        var plugin = new MetricsPlugin(config);
        var ctx = createTestContext();

        plugin.onInitialize(ctx);

        assertTrue(ctx.getService(MetricsService.class).isPresent());
        assertEquals("/metrics", ctx.getRegisteredRoutes().get(0));
    }

    private static VNodeContextImpl createTestContext() {
        var json = new VJsonImpl();
        var firewall = new VFirewallImpl();
        Map<Class<?>, Object> services = new HashMap<>();
        services.put(VJson.class, json);
        return new VNodeContextImpl("test-node", firewall, services);
    }
}
