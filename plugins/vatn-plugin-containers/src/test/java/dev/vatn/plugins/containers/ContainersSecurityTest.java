package dev.vatn.plugins.containers;

import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Tag("security")
class ContainersSecurityTest {

    private static final String TEST_TOKEN = "test-container-token-99999";
    private static final String BASE = "/vatn/containers";
    private static final HttpClient client = HttpClient.newHttpClient();

    private VNodeRunner runner;

    @BeforeEach
    void setUp() {
        ContainersPlugin.setStaticToken(TEST_TOKEN);
    }

    @AfterEach
    void stopNode() {
        if (runner != null) runner.stop();
        ContainersPlugin.setStaticToken(null);
    }

    @Test
    void noAuthHeaderReturns401() throws Exception {
        startNode();
        var r = get(BASE + "/api/containers", null);
        assertEquals(401, r.statusCode());
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        startNode();
        var r = get(BASE + "/api/containers", "Bearer wrong-token");
        assertEquals(401, r.statusCode());
    }

    @Test
    void emptyTokenReturns401() throws Exception {
        startNode();
        var r = get(BASE + "/api/containers", "Bearer ");
        assertEquals(401, r.statusCode());
    }

    @Test
    void validTokenReturns200() throws Exception {
        startNode();
        var r = get(BASE + "/api/containers", "Bearer " + TEST_TOKEN);
        assertEquals(200, r.statusCode());
    }

    @Test
    void htmlPageServedFreely() throws Exception {
        startNode();
        var r = get(BASE, null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("VATN Containers"), "HTML page should be served without auth");
    }

    @Test
    void allApiEndpointsRequireAuth() throws Exception {
        startNode();
        String[] endpoints = {
            "/api/node-id", "/api/system-status", "/api/resources", "/api/health",
            "/api/containers", "/api/profiles", "/api/stacks", "/api/templates",
            "/api/routes", "/api/trigger-refresh"
        };
        for (String ep : endpoints) {
            var r = get(BASE + ep, null);
            assertEquals(401, r.statusCode(), "Endpoint " + ep + " should require auth");
        }
    }

    @Test
    void wrongAuthSchemeReturns401() throws Exception {
        startNode();
        var r = get(BASE + "/api/containers", "Basic " + TEST_TOKEN);
        assertEquals(401, r.statusCode());
    }

    @Test
    void nodeIdEndpointRequiresAuth() throws Exception {
        startNode();
        var r = get(BASE + "/api/node-id", null);
        assertEquals(401, r.statusCode());
    }

    @Test
    void nodeIdEndpointWithValidTokenReturns200() throws Exception {
        startNode();
        var r = get(BASE + "/api/node-id", "Bearer " + TEST_TOKEN);
        assertEquals(200, r.statusCode());
    }

    private void startNode() {
        runner = VNodeRunner.create(0);
        runner.addPlugin(new ContainersPlugin());
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
