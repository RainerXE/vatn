package dev.vatn.plugins.slack;

import com.sun.net.httpserver.HttpServer;
import dev.vatn.core.VNodeContextImpl;
import dev.vatn.core.security.VFirewallImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SlackPluginTest {

    @Test
    void pluginInitializesAndRegistersService() {
        var ctx = new VNodeContextImpl("test", new VFirewallImpl(), Map.of());
        var plugin = new SlackPlugin(SlackConfig.of("https://hooks.slack.com/services/test"));
        plugin.onInitialize(ctx);

        assertTrue(ctx.getService(SlackService.class).isPresent());
        assertTrue(ctx.getHealthChecks().containsKey("slack"));
    }

    @Test
    void healthCheckReturnsTrueWithValidUrl() {
        var ctx = new VNodeContextImpl("test", new VFirewallImpl(), Map.of());
        var plugin = new SlackPlugin(SlackConfig.of("https://hooks.slack.com/services/test"));
        plugin.onInitialize(ctx);

        assertTrue(ctx.getHealthChecks().get("slack").get());
    }

    @Test
    void healthCheckReturnsFalseWithBlankUrl() {
        var ctx = new VNodeContextImpl("test", new VFirewallImpl(), Map.of());
        var plugin = new SlackPlugin(SlackConfig.of(""));
        plugin.onInitialize(ctx);

        assertFalse(ctx.getHealthChecks().get("slack").get());
    }

    @Test
    void healthCheckReturnsFalseWithNullUrl() {
        var ctx = new VNodeContextImpl("test", new VFirewallImpl(), Map.of());
        var plugin = new SlackPlugin(SlackConfig.of(null));
        plugin.onInitialize(ctx);

        assertFalse(ctx.getHealthChecks().get("slack").get());
    }

    @Test
    void webhookRetriesOn429ThenSucceeds() throws Exception {
        var callCount = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (callCount.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var service = new SlackWebhookService(SlackConfig.of("http://localhost:" + port));
            service.notify("test");
            assertEquals(2, callCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webhookDoesNotRetryOn400() throws Exception {
        var callCount = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var service = new SlackWebhookService(SlackConfig.of("http://localhost:" + port));
            assertThrows(RuntimeException.class, () -> service.notify("test"));
            assertEquals(1, callCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webhookRetriesOn503WithExponentialBackoff() throws Exception {
        var callCount = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = callCount.incrementAndGet();
            if (n < 3) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var service = new SlackWebhookService(SlackConfig.of("http://localhost:" + port));
            service.notify("test");
            assertEquals(3, callCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webhookThrowsAfterMaxRetriesOn5xx() throws Exception {
        var callCount = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(502, -1);
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var service = new SlackWebhookService(SlackConfig.of("http://localhost:" + port));
            assertThrows(RuntimeException.class, () -> service.notify("test"));
            assertEquals(4, callCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webhookRetriesOnIOException() {
        var service = new SlackWebhookService(SlackConfig.of("http://localhost:1"));
        assertThrows(IOException.class, () -> service.notify("test"));
    }

    @Test
    void parseRetryAfterSeconds() {
        assertEquals(3000L, SlackWebhookService.parseRetryAfter("2"));
    }

    @Test
    void parseRetryAfterHttpDate() {
        long result = SlackWebhookService.parseRetryAfter("Thu, 01 Jan 2099 00:00:00 GMT");
        assertTrue(result > 1000L);
    }

    @Test
    void parseRetryAfterInvalidReturnsDefault() {
        assertEquals(5000L, SlackWebhookService.parseRetryAfter("garbage"));
    }
}
