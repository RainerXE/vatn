package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.plugins.cors.CorsConfig;
import dev.vatn.plugins.cors.CorsPlugin;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for the CORS plugin (threat-model surface S8).
 *
 * <p>Boots real nodes with restricted and wildcard CORS configs and attacks origin
 * matching: disallowed origins, suffix/case bypass attempts, null origins, preflight
 * behavior, and the dangerous wildcard+credentials combination (the bug class Helidon
 * 4.5.0 itself patched upstream).
 */
@Tag("adversarial")
class CorsAdversarialTest {

    private static final String GOOD = "https://app.example.com";

    private static VNodeRunner restricted;   // allowlist: GOOD only, no credentials
    private static VNodeRunner wildcardCred; // wildcard origin + credentials (dangerous combo)
    private static int restrictedPort;
    private static int wildcardCredPort;
    private static HttpClient http;
    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    /** A trivial plugin whose route goes through the VHttpFilter chain (unlike built-in /vatn/* routes). */
    private static dev.vatn.api.VNodePlugin testApi() {
        return new dev.vatn.api.VNodePlugin() {
            @Override public String getId()      { return "dev.vatn.verify.cors-test-api"; }
            @Override public String getName()    { return "CORS Test API"; }
            @Override public String getVersion() { return "1.0"; }
            @Override public void onInitialize(dev.vatn.api.VNodeContext ctx) {
                ctx.register("/api", routes -> routes.get("/data",
                        (req, res) -> res.sendJson("{\"ok\":true}")));
            }
        };
    }

    @BeforeAll
    static void startNodes() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("vatn-cors-adv-");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        restricted = VNodeRunner.create(0);
        restricted.addPlugin(new CorsPlugin(CorsConfig.of(GOOD)));
        restricted.addPlugin(testApi());
        restricted.start();
        restrictedPort = restricted.getBoundPort();

        wildcardCred = VNodeRunner.create(0);
        wildcardCred.addPlugin(new CorsPlugin(CorsConfig.permissive().withCredentials(true)));
        wildcardCred.addPlugin(testApi());
        wildcardCred.start();
        wildcardCredPort = wildcardCred.getBoundPort();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @AfterAll
    static void stopNodes() throws Exception {
        if (restricted != null) restricted.stop();
        if (wildcardCred != null) wildcardCred.stop();
        System.setProperty("user.home", ORIGINAL_USER_HOME);
    }

    // =========================================================================
    // Origin matching (restricted node)
    // =========================================================================

    @Test
    @DisplayName("Disallowed origin → NO Access-Control-Allow-Origin header")
    void disallowedOriginGetsNoAcao() throws Exception {
        HttpResponse<String> resp = getWithOrigin(restrictedPort, "https://evil.example.com");
        assertFalse(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "disallowed origin must receive no ACAO header");
    }

    @Test
    @DisplayName("Allowed origin → ACAO echoes exactly that origin + Vary: Origin")
    void allowedOriginEchoedExactly() throws Exception {
        HttpResponse<String> resp = getWithOrigin(restrictedPort, GOOD);
        assertEquals(GOOD, resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals("Origin", resp.headers().firstValue("Vary").orElse(null));
    }

    @Test
    @DisplayName("Suffix bypass attempt (good.com.evil.com) → no ACAO")
    void suffixBypassRejected() throws Exception {
        HttpResponse<String> resp = getWithOrigin(restrictedPort, "https://app.example.com.evil.com");
        assertFalse(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "suffix-spoofed origin must not match the allowlist");
    }

    @Test
    @DisplayName("Trailing-slash variant of an allowed origin → no ACAO (strict matching)")
    void trailingSlashStrictMatch() throws Exception {
        HttpResponse<String> resp = getWithOrigin(restrictedPort, GOOD + "/");
        assertFalse(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "trailing-slash variant must not bypass exact matching");
    }

    @Test
    @DisplayName("Origin 'null' (sandboxed iframe) → no ACAO")
    void nullOriginRejected() throws Exception {
        HttpResponse<String> resp = getWithOrigin(restrictedPort, "null");
        assertFalse(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "literal 'null' origin must not be honored");
    }

    @Test
    @DisplayName("Preflight from disallowed origin → no ACAO in the 204 response")
    void preflightDisallowedOrigin() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + restrictedPort + "/api/data"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET")
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertFalse(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "preflight from a disallowed origin must not grant ACAO");
    }

    // =========================================================================
    // Wildcard + credentials combo (the Helidon-4.5 bug class)
    // =========================================================================

    @Test
    @DisplayName("Wildcard origin + credentials → ACAC must be suppressed (browser-invalid combo)")
    void wildcardCredentialsComboSuppressed() throws Exception {
        HttpResponse<String> resp = getWithOrigin(wildcardCredPort, "https://evil.example.com");
        String acao = resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
        String acac = resp.headers().firstValue("Access-Control-Allow-Credentials").orElse(null);
        assertFalse("*".equals(acao) && "true".equalsIgnoreCase(acac),
                "must never emit ACAO:'*' together with ACAC:'true' — got ACAO=" + acao + " ACAC=" + acac);
    }

    @Test
    @DisplayName("Control: wildcard origin WITHOUT credentials still emits ACAO:* (legit public API)")
    void wildcardWithoutCredentialsAllowed() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("vatn-cors-adv3-");
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        VNodeRunner open = VNodeRunner.create(0);
        try {
            open.addPlugin(new CorsPlugin(CorsConfig.permissive())); // wildcard, no credentials
            open.addPlugin(testApi());
            open.start();
            HttpResponse<String> resp = getWithOrigin(open.getBoundPort(), "https://anything.example.com");
            assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertFalse(resp.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
        } finally {
            open.stop();
            System.setProperty("user.home", prevHome);
        }
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private HttpResponse<String> getWithOrigin(int p, String origin) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + p + "/api/data"))
                        .GET()
                        .header("Origin", origin)
                        .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
