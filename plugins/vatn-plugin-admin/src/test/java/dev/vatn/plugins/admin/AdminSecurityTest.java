package dev.vatn.plugins.admin;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Tag("security")
class AdminSecurityTest {

    private static final String TEST_TOKEN = "test-admin-token-12345";
    private static final String BASE = "/vatn/admin";
    private static final HttpClient client = HttpClient.newHttpClient();

    private VNodeRunner runner;

    @AfterEach
    void stopNode() {
        if (runner != null) runner.stop();
    }

    // ── Auth bypass tests ─────────────────────────────────────────────────

    @Test
    void noAuthHeaderReturns401() throws Exception {
        startNodeWithAuth();
        var r = get(BASE + "/api/plugins", null);
        assertEquals(401, r.statusCode());
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        startNodeWithAuth();
        var r = get(BASE + "/api/plugins", "Bearer wrong-token");
        assertEquals(401, r.statusCode());
    }

    @Test
    void emptyTokenReturns401() throws Exception {
        startNodeWithAuth();
        var r = get(BASE + "/api/plugins", "Bearer ");
        assertEquals(401, r.statusCode());
    }

    @Test
    void validTokenReturns200() throws Exception {
        startNodeWithAuth();
        var r = get(BASE + "/api/plugins", "Bearer " + TEST_TOKEN);
        assertEquals(200, r.statusCode());
    }

    @Test
    void allApiEndpointsRequireAuth() throws Exception {
        startNodeWithAuth();
        String[] endpoints = {"/api/overview", "/api/plugins", "/api/health",
                "/api/agents", "/api/workflows", "/api/routes", "/api/jvm", "/api/queues"};
        for (String ep : endpoints) {
            var r = get(BASE + ep, null);
            assertEquals(401, r.statusCode(), "Endpoint " + ep + " should require auth");
        }
    }

    @Test
    void allFragmentEndpointsRequireAuth() throws Exception {
        startNodeWithAuth();
        String[] endpoints = {"/fragments/overview", "/fragments/plugins",
                "/fragments/health", "/fragments/workflows", "/fragments/workloads",
                "/fragments/jvm", "/fragments/queues", "/fragments/routes"};
        for (String ep : endpoints) {
            var r = get(BASE + ep, null);
            assertEquals(401, r.statusCode(), "Fragment " + ep + " should require auth");
        }
    }

    @Test
    void htmlDashboardServedFreely() throws Exception {
        startNodeWithAuth();
        var r = get(BASE, null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("VATN Admin"), "HTML dashboard should contain page content");
    }

    @Test
    void wrongAuthSchemeReturns401() throws Exception {
        startNodeWithAuth();
        var r = get(BASE + "/api/plugins", "Basic " + TEST_TOKEN);
        assertEquals(401, r.statusCode());
    }

    // ── XSS tests ─────────────────────────────────────────────────────────

    @Test
    void pluginNameWithHtmlIsEscapedInFragment() throws Exception {
        String maliciousName = "<script>alert('xss')</script>";
        startNodeWithAuth(new VNodePlugin() {
            @Override public String getId() { return "test.xss"; }
            @Override public String getName() { return maliciousName; }
            @Override public String getVersion() { return "1.0"; }
            @Override public void onInitialize(VNodeContext ctx) {}
            @Override public void onShutdown() {}
        });

        var r = get(BASE + "/fragments/plugins", "Bearer " + TEST_TOKEN);
        String body = r.body();
        assertTrue(body.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"),
                "Plugin name must be HTML-escaped in fragment response");
        assertFalse(body.contains("<script>alert('xss')</script>"),
                "Raw HTML must not appear in fragment response");
    }

    @Test
    void pluginIdWithHtmlIsEscapedInFragment() throws Exception {
        String maliciousId = "test.<img src=x onerror=alert(1)>.xss";
        startNodeWithAuth(new VNodePlugin() {
            @Override public String getId() { return maliciousId; }
            @Override public String getName() { return "XSS test"; }
            @Override public String getVersion() { return "1.0"; }
            @Override public void onInitialize(VNodeContext ctx) {}
            @Override public void onShutdown() {}
        });

        var r = get(BASE + "/fragments/plugins", "Bearer " + TEST_TOKEN);
        String body = r.body();
        assertTrue(body.contains("&lt;img"), "Plugin ID must be HTML-escaped in fragment response");
        assertFalse(body.contains("<img"), "Raw HTML tags must not appear in fragment response");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void startNodeWithAuth(VNodePlugin... extraPlugins) {
        runner = VNodeRunner.create(0);
        runner.addPlugin(new AdminPlugin(AdminConfig.defaults().withToken(TEST_TOKEN)));
        for (var p : extraPlugins) runner.addPlugin(p);
        runner.start();
    }

    private HttpResponse<String> get(String path, String authHeader) throws Exception {
        var b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + runner.getBoundPort() + path))
                .GET();
        if (authHeader != null) b.header("Authorization", authHeader);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
