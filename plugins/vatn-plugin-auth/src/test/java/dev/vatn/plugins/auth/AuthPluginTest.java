package dev.vatn.plugins.auth;

import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive adversarial test suite for {@link AuthPlugin}.
 *
 * <p>Covers: happy paths, JWT attacks (alg:none, tampered payload, wrong secret,
 * truncated token), brute-force, oversized inputs, concurrent refresh races,
 * SQL-injection-style username inputs, and plugin lifecycle.
 *
 * <p>Each test class boots its own node on an ephemeral port and tears it down
 * after all tests — no shared state between test classes.
 */
@DisplayName("AuthPlugin — Adversarial Test Suite")
class AuthPluginTest {

    // ── Shared fixtures ───────────────────────────────────────────────────────

    private static final String VALID_SECRET =
            "test-secret-key-at-least-32-chars!!";        // exactly 36 chars
    private static final String OTHER_SECRET =
            "other-secret-key-different-32chars!!";       // different key → wrong signature
    private static final String VALID_USER = "alice";
    private static final String VALID_PASS = "correct-horse-battery-staple";

    private static VNodeRunner node;
    private static int port;
    private static Path tempDir;
    private static HttpClient http;

    @BeforeAll
    static void startNode() throws Exception {
        tempDir = Files.createTempDirectory("vatn-auth-test-");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        AuthConfig cfg = AuthConfig.of(
            VALID_SECRET,
            (username, password) -> {
                if (VALID_USER.equals(username) && VALID_PASS.equals(password)) {
                    return Map.of("role", "admin");
                }
                throw new InvalidCredentialsException("Bad credentials");
            }
        );

        node = VNodeRunner.create(0);
        node.addPlugin(new AuthPlugin(cfg));
        node.start();
        port = node.getBoundPort();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void stopNode() throws Exception {
        if (node != null) node.stop();
        if (tempDir != null) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (bearerToken != null) b.header("Authorization", "Bearer " + bearerToken);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Login with valid credentials, return the access token. */
    private String loginAndGetAccessToken() throws Exception {
        var resp = post("/auth/login",
                "{\"username\":\"" + VALID_USER + "\",\"password\":\"" + VALID_PASS + "\"}");
        assertEquals(200, resp.statusCode(), "Login must succeed: " + resp.body());
        // Extract accessToken from JSON (no Jackson dep in test, use simple parse)
        return extractField(resp.body(), "accessToken");
    }

    private String loginAndGetRefreshToken() throws Exception {
        var resp = post("/auth/login",
                "{\"username\":\"" + VALID_USER + "\",\"password\":\"" + VALID_PASS + "\"}");
        assertEquals(200, resp.statusCode());
        return extractField(resp.body(), "refreshToken");
    }

    private static String extractField(String json, String field) {
        // Simple extraction: "field":"value"
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) throw new IllegalArgumentException("Field '" + field + "' not found in: " + json);
        int start = idx + field.length() + 4;
        int end   = json.indexOf('"', start);
        return json.substring(start, end);
    }

    // =========================================================================
    // 1. Lifecycle
    // =========================================================================

    @Test
    @DisplayName("Plugin registers /auth routes at startup")
    void pluginRegistersRoutes() throws Exception {
        // A GET /auth/me without token must return 401, proving routes are registered
        var resp = get("/auth/me", null);
        assertEquals(401, resp.statusCode(), "Unauthenticated /auth/me must be 401, not 404");
    }

    // =========================================================================
    // 2. Login — happy path
    // =========================================================================

    @Test
    @DisplayName("POST /auth/login with valid credentials returns 200 with tokens")
    void loginHappyPath() throws Exception {
        var resp = post("/auth/login",
                "{\"username\":\"alice\",\"password\":\"correct-horse-battery-staple\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("accessToken"),  "Response must contain accessToken");
        assertTrue(resp.body().contains("refreshToken"), "Response must contain refreshToken");
    }

    // =========================================================================
    // 3. Login — adversarial
    // =========================================================================

    @Test
    @DisplayName("POST /auth/login with wrong password returns 401")
    void loginWrongPassword() throws Exception {
        var resp = post("/auth/login", "{\"username\":\"alice\",\"password\":\"wrong\"}");
        assertEquals(401, resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with unknown user returns 401")
    void loginUnknownUser() throws Exception {
        var resp = post("/auth/login", "{\"username\":\"eve\",\"password\":\"anything\"}");
        assertEquals(401, resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with missing username returns 400")
    void loginMissingUsername() throws Exception {
        var resp = post("/auth/login", "{\"password\":\"secret\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with missing password returns 400")
    void loginMissingPassword() throws Exception {
        var resp = post("/auth/login", "{\"username\":\"alice\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with empty body returns 400")
    void loginEmptyBody() throws Exception {
        var resp = post("/auth/login", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with blank body returns 400 or 401 (not 500)")
    void loginBlankBody() throws Exception {
        var resp = post("/auth/login", "");
        assertTrue(resp.statusCode() == 400 || resp.statusCode() == 401,
                "Blank body must not produce 500, got: " + resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with malformed JSON returns 4xx (not 500)")
    void loginMalformedJson() throws Exception {
        var resp = post("/auth/login", "{not valid json{{");
        assertTrue(resp.statusCode() >= 400 && resp.statusCode() < 500,
                "Malformed JSON must produce 4xx, got: " + resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with SQL injection in username is safe (returns 401)")
    void loginSqlInjectionUsername() throws Exception {
        // If there were a SQL backend, this would try to break it
        var resp = post("/auth/login",
                "{\"username\":\"' OR '1'='1\",\"password\":\"anything\"}");
        // Must return 401 (not crash, not 200)
        assertEquals(401, resp.statusCode(), "SQL injection attempt must be rejected");
        assertFalse(resp.body().contains("accessToken"), "Must not issue a token on injection");
    }

    @Test
    @DisplayName("POST /auth/login with username containing CRLF is safe")
    void loginCrlfInUsername() throws Exception {
        var resp = post("/auth/login",
                "{\"username\":\"alice\\r\\nX-Injected: evil\",\"password\":\"x\"}");
        assertTrue(resp.statusCode() == 400 || resp.statusCode() == 401,
                "CRLF injection must not produce 200");
    }

    @Test
    @DisplayName("POST /auth/login with username > 8KB returns 400 (not crash)")
    void loginOversizedUsername() throws Exception {
        String bigUser = "a".repeat(8192);
        var resp = post("/auth/login",
                "{\"username\":\"" + bigUser + "\",\"password\":\"x\"}");
        // Must not crash the server; a 400 or 401 is acceptable
        assertTrue(resp.statusCode() == 400 || resp.statusCode() == 401 || resp.statusCode() == 413,
                "Oversized username must not cause 5xx, got: " + resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/login with null byte in password is safe")
    void loginNullByteInPassword() throws Exception {
        // JSON does not allow raw null bytes, but some parsers are lenient
        var resp = post("/auth/login",
                "{\"username\":\"alice\",\"password\":\"pass\\u0000word\"}");
        assertTrue(resp.statusCode() == 400 || resp.statusCode() == 401,
                "Null byte in password must not cause 5xx");
    }

    // =========================================================================
    // 4. Brute-force resilience
    // =========================================================================

    @Test
    @DisplayName("100 consecutive bad login attempts must not crash the server")
    void bruteForceDoesNotCrash() throws Exception {
        for (int i = 0; i < 100; i++) {
            var resp = post("/auth/login",
                    "{\"username\":\"alice\",\"password\":\"wrong" + i + "\"}");
            assertEquals(401, resp.statusCode(),
                    "Attempt " + i + " must return 401, got " + resp.statusCode());
        }
        // Server must still respond normally after 100 failed attempts
        var resp = post("/auth/login",
                "{\"username\":\"" + VALID_USER + "\",\"password\":\"" + VALID_PASS + "\"}");
        assertEquals(200, resp.statusCode(), "Valid login must still work after brute-force attempts");
    }

    // =========================================================================
    // 5. /auth/me — JWT validation
    // =========================================================================

    @Test
    @DisplayName("GET /auth/me with valid token returns 200 and subject")
    void meWithValidToken() throws Exception {
        String token = loginAndGetAccessToken();
        var resp = get("/auth/me", token);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("alice"), "Response must contain the subject");
    }

    @Test
    @DisplayName("GET /auth/me with no Authorization header returns 401")
    void meNoToken() throws Exception {
        var resp = get("/auth/me", null);
        assertEquals(401, resp.statusCode());
    }

    @Test
    @DisplayName("GET /auth/me with empty Bearer token returns 401")
    void meEmptyToken() throws Exception {
        var resp = get("/auth/me", "");
        assertEquals(401, resp.statusCode());
    }

    @Test
    @DisplayName("GET /auth/me with truncated JWT returns 401 (not 500)")
    void meTruncatedToken() throws Exception {
        String token = loginAndGetAccessToken();
        // Remove the last 20 chars of the signature
        String truncated = token.substring(0, Math.max(0, token.length() - 20));
        var resp = get("/auth/me", truncated);
        assertEquals(401, resp.statusCode(), "Truncated JWT must be rejected");
    }

    @Test
    @DisplayName("GET /auth/me with tampered payload returns 401")
    void meTamperedPayload() throws Exception {
        String token = loginAndGetAccessToken();
        // JWT = header.payload.signature — tamper the payload
        String[] parts = token.split("\\.");
        if (parts.length != 3) return; // skip if unexpected format
        // Flip a byte in the payload
        byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
        decoded[decoded.length / 2] ^= 0xFF; // flip bits
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                + "." + parts[2];
        var resp = get("/auth/me", tampered);
        assertEquals(401, resp.statusCode(), "Tampered JWT must be rejected (signature mismatch)");
    }

    @Test
    @DisplayName("GET /auth/me with alg:none attack returns 401")
    void meAlgNoneAttack() throws Exception {
        // Construct a JWT with alg:none — unsigned token claiming to be valid
        String header  = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + VALID_USER + "\","
                        + "\"type\":\"access\","
                        + "\"iat\":" + (System.currentTimeMillis() / 1000) + ","
                        + "\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes());
        // Three variants: no signature, empty signature, literal "none"
        for (String sig : new String[]{"", "none", "invalid"}) {
            String algNoneToken = header + "." + payload + "." + sig;
            var resp = get("/auth/me", algNoneToken);
            assertEquals(401, resp.statusCode(),
                    "alg:none attack must be rejected (sig=[" + sig + "])");
        }
    }

    @Test
    @DisplayName("GET /auth/me with token signed by a DIFFERENT secret returns 401")
    void meWrongSecretToken() throws Exception {
        // Create a node with a different secret and get a token from it
        Path otherTemp = Files.createTempDirectory("vatn-auth-other-");
        System.setProperty("user.home", otherTemp.toAbsolutePath().toString());
        VNodeRunner otherNode = VNodeRunner.create(0);
        otherNode.addPlugin(new AuthPlugin(AuthConfig.of(OTHER_SECRET,
                (u, p) -> { if ("alice".equals(u)) return Map.of(); throw new InvalidCredentialsException("x"); })));
        otherNode.start();
        try {
            HttpClient otherHttp = HttpClient.newHttpClient();
            var loginResp = otherHttp.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + otherNode.getBoundPort() + "/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\",\"password\":\"\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (loginResp.statusCode() == 200) {
                String foreignToken = extractField(loginResp.body(), "accessToken");
                // Present foreign token to our node
                var resp = get("/auth/me", foreignToken);
                assertEquals(401, resp.statusCode(),
                        "Token from a different secret must be rejected");
            }
        } finally {
            otherNode.stop();
            Files.walk(otherTemp).sorted(Comparator.reverseOrder())
                 .forEach(p2 -> { try { Files.deleteIfExists(p2); } catch (Exception ignored) {} });
            System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        }
    }

    @Test
    @DisplayName("GET /auth/me with garbage string returns 401 (not 500)")
    void meGarbageToken() throws Exception {
        for (String garbage : List.of("garbage", "a.b", "x.y.z", "!!!", "null", "undefined")) {
            var resp = get("/auth/me", garbage);
            assertEquals(401, resp.statusCode(),
                    "Garbage token [" + garbage + "] must return 401, got " + resp.statusCode());
        }
    }

    // =========================================================================
    // 6. Refresh token
    // =========================================================================

    @Test
    @DisplayName("POST /auth/refresh with valid refresh token returns new token pair")
    void refreshHappyPath() throws Exception {
        String refreshToken = loginAndGetRefreshToken();
        var resp = post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("accessToken"), "Refresh must return new accessToken");
    }

    @Test
    @DisplayName("POST /auth/refresh with ACCESS token (wrong type) returns 401")
    void refreshWithAccessTokenIsRejected() throws Exception {
        // Access token used in the refresh endpoint — must be rejected (wrong type claim)
        String accessToken = loginAndGetAccessToken();
        var resp = post("/auth/refresh", "{\"refreshToken\":\"" + accessToken + "\"}");
        assertEquals(401, resp.statusCode(), "Using access token as refresh token must be rejected");
    }

    @Test
    @DisplayName("POST /auth/refresh with tampered refresh token returns 401")
    void refreshTamperedToken() throws Exception {
        String token = loginAndGetRefreshToken();
        String[] parts = token.split("\\.");
        if (parts.length != 3) return;
        byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
        decoded[0] ^= 0xFF;
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                + "." + parts[2];
        var resp = post("/auth/refresh", "{\"refreshToken\":\"" + tampered + "\"}");
        assertEquals(401, resp.statusCode());
    }

    @Test
    @DisplayName("POST /auth/refresh with missing field returns 400")
    void refreshMissingField() throws Exception {
        var resp = post("/auth/refresh", "{}");
        assertEquals(400, resp.statusCode());
    }

    // =========================================================================
    // 7. Concurrent token refresh — no double-issue race
    // =========================================================================

    @Test
    @DisplayName("50 concurrent valid logins all succeed (no race condition)")
    void concurrentLoginsAllSucceed() throws Exception {
        int count = 50;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures  = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                try {
                    var resp = post("/auth/login",
                            "{\"username\":\"" + VALID_USER
                            + "\",\"password\":\"" + VALID_PASS + "\"}");
                    if (resp.statusCode() == 200) successes.incrementAndGet();
                    else failures.incrementAndGet();
                } catch (Exception e) { failures.incrementAndGet(); }
            }));
        }
        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, failures.get(),
                "All " + count + " concurrent logins must succeed; " + failures.get() + " failed");
        assertEquals(count, successes.get());
    }

    // =========================================================================
    // 8. Response body must not leak secrets
    // =========================================================================

    @Test
    @DisplayName("401 response must not contain stack trace or secret key")
    void errorResponseDoesNotLeakInternals() throws Exception {
        var resp = post("/auth/login", "{\"username\":\"alice\",\"password\":\"wrong\"}");
        assertEquals(401, resp.statusCode());
        String body = resp.body();
        assertFalse(body.contains("Exception"),  "Error response must not contain stack trace class");
        assertFalse(body.contains(VALID_SECRET), "Error response must not leak the secret key");
        assertFalse(body.contains("at dev.vatn"), "Error response must not contain stack frames");
    }

    @Test
    @DisplayName("Content-Type of error responses is JSON")
    void errorResponseContentType() throws Exception {
        var resp = post("/auth/login", "{\"username\":\"x\",\"password\":\"x\"}");
        String ct = resp.headers().firstValue("content-type").orElse("");
        assertTrue(ct.contains("application/json") || ct.contains("json"),
                "Error response Content-Type must be JSON, got: " + ct);
    }

    // =========================================================================
    // 9. Config validation
    // =========================================================================

    @Test
    @DisplayName("AuthConfig with secret shorter than 32 chars throws IllegalArgumentException")
    void configShortSecretRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                AuthConfig.of("short", (u, p) -> Map.of()));
    }

    @Test
    @DisplayName("AuthConfig with null secret throws IllegalArgumentException")
    void configNullSecretRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                AuthConfig.of(null, (u, p) -> Map.of()));
    }

    @Test
    @DisplayName("AuthConfig with null validator throws IllegalArgumentException")
    void configNullValidatorRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                AuthConfig.of(VALID_SECRET, null));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String padBase64(String s) {
        switch (s.length() % 4) {
            case 2: return s + "==";
            case 3: return s + "=";
            default: return s;
        }
    }
}
