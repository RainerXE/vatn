package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.plugins.auth.AuthConfig;
import dev.vatn.plugins.auth.AuthPlugin;
import dev.vatn.plugins.auth.InvalidCredentialsException;
import org.junit.jupiter.api.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for the JWT auth plugin (threat-model surface S7).
 *
 * <p>Boots a real node with {@link AuthPlugin} and attacks it over HTTP: credential
 * brute-force behavior, malformed bodies, forged/tampered/alg=none/expired tokens,
 * token-type confusion, header abuse. The node must repel every attack with a clean
 * 4xx — never a 500 oracle, never a crash.
 */
@Tag("adversarial")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthAdversarialTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-32-chars-long!!";
    private static final String WRONG_SECRET = "attacker-secret-key-at-least-32-chars-long!!";

    private static VNodeRunner node;
    private static int port;
    private static HttpClient http;
    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    @BeforeAll
    static void startNode() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("vatn-auth-adv-");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        AuthConfig config = AuthConfig.of(SECRET, (username, password) -> {
            if ("alice".equals(username) && "correct-horse".equals(password)) {
                return java.util.Map.of("role", "admin");
            }
            throw new InvalidCredentialsException("bad credentials");
        });

        node = VNodeRunner.create(0);
        node.addPlugin(new AuthPlugin(config));
        node.start();
        port = node.getBoundPort();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @AfterAll
    static void stopNode() throws Exception {
        if (node != null) node.stop();
        System.setProperty("user.home", ORIGINAL_USER_HOME);
    }

    // =========================================================================
    // Credential attacks
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Wrong password → uniform 401, no oracle distinguishing user vs password")
    void wrongPasswordUniform401() throws Exception {
        HttpResponse<String> resp = login("alice", "wrong-password");
        assertEquals(401, resp.statusCode());
        assertTrue(resp.body().contains("Invalid credentials"));
        // must not reveal which part failed
        assertFalse(resp.body().toLowerCase().contains("password"));
        assertFalse(resp.body().toLowerCase().contains("user"));
    }

    @Test
    @Order(2)
    @DisplayName("Validator throwing a raw RuntimeException still yields 401, never 500")
    void sloppyValidatorStillYields401() throws Exception {
        // A second node whose validator throws an UNCHECKED generic exception — exactly what
        // VatnWebAdmin's validator does today. The login path must never leak a 500 oracle.
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("vatn-auth-adv2-");
        String prevHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        VNodeRunner sloppy = VNodeRunner.create(0);
        try {
            AuthConfig cfg = AuthConfig.of(SECRET, (u, p) -> { throw new RuntimeException("nope"); });
            sloppy.addPlugin(new AuthPlugin(cfg));
            sloppy.start();
            int sloppyPort = sloppy.getBoundPort();

            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + sloppyPort + "/auth/login"))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"a\",\"password\":\"b\"}"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, resp.statusCode(),
                    "Validator RuntimeException must map to 401, not leak as 500. Body: " + resp.body());
        } finally {
            sloppy.stop();
            System.setProperty("user.home", prevHome);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Blank body → 400 (regression: must not be 500)")
    void blankBody400() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/auth/login"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }

    @Test
    @Order(4)
    @DisplayName("Garbage body → 400 (not 500)")
    void garbageBody400() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/auth/login"))
                        .POST(HttpRequest.BodyPublishers.ofString("this is not json{{{"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode());
    }

    @Test
    @Order(5)
    @DisplayName("Missing fields → 400")
    void missingFields400() throws Exception {
        HttpResponse<String> resp = loginJson("{\"username\":\"alice\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    @Order(6)
    @DisplayName("50 rapid-fire wrong logins → all 401, node stays healthy")
    void rapidFireLogins() throws Exception {
        for (int i = 0; i < 50; i++) {
            HttpResponse<String> resp = login("alice", "wrong-" + i);
            assertEquals(401, resp.statusCode(), "attempt " + i);
        }
        // node still healthy after the burst
        HttpResponse<String> me = me(null);
        assertEquals(401, me.statusCode(), "node must still be responsive after brute-force burst");
    }

    // =========================================================================
    // Token attacks
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("/me without Authorization header → 401")
    void meWithoutToken401() throws Exception {
        assertEquals(401, me(null).statusCode());
    }

    @Test
    @Order(11)
    @DisplayName("/me with garbage bearer → 401")
    void meGarbageBearer401() throws Exception {
        assertEquals(401, me("garbage.not-a.token").statusCode());
    }

    @Test
    @Order(12)
    @DisplayName("Forged token (valid structure, wrong signing key) → 401")
    void forgedTokenWrongSecret401() throws Exception {
        String forged = jwt(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"alice\",\"iss\":\"vatn-auth\",\"type\":\"access\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}",
                WRONG_SECRET);
        assertEquals(401, me(forged).statusCode());
    }

    @Test
    @Order(13)
    @DisplayName("Tampered payload (signature kept) → 401")
    void tamperedPayload401() throws Exception {
        String real = loginForAccessToken();
        String[] parts = real.split("\\.");
        assertEquals(3, parts.length);
        // swap in an attacker-controlled payload (role=superadmin) but keep the original signature
        String evilPayload = b64url(("{\"sub\":\"alice\",\"iss\":\"vatn-auth\",\"type\":\"access\",\"role\":\"superadmin\",\"exp\":"
                + (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + evilPayload + "." + parts[2];
        assertEquals(401, me(tampered).statusCode());
    }

    @Test
    @Order(14)
    @DisplayName("alg=none token → 401 (algorithm confusion)")
    void algNoneToken401() throws Exception {
        String header = b64url("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64url(("{\"sub\":\"alice\",\"iss\":\"vatn-auth\",\"type\":\"access\",\"exp\":"
                + (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes(StandardCharsets.UTF_8));
        String noneToken = header + "." + payload + ".";
        assertEquals(401, me(noneToken).statusCode());
    }

    @Test
    @Order(15)
    @DisplayName("Expired but correctly-signed token → 401")
    void expiredToken401() throws Exception {
        String expired = jwt(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"alice\",\"iss\":\"vatn-auth\",\"type\":\"access\",\"exp\":" + (System.currentTimeMillis() / 1000 - 3600) + "}",
                SECRET);
        assertEquals(401, me(expired).statusCode());
    }

    @Test
    @Order(16)
    @DisplayName("Refresh token presented as access token → 401 (type confusion)")
    void refreshAsAccess401() throws Exception {
        HttpResponse<String> loginResp = login("alice", "correct-horse");
        assertEquals(200, loginResp.statusCode());
        String refresh = extract(loginResp.body(), "refreshToken");
        assertNotNull(refresh);
        assertEquals(401, me(refresh).statusCode());
    }

    @Test
    @Order(17)
    @DisplayName("Access token presented to /refresh → 401 (type confusion)")
    void accessAsRefresh401() throws Exception {
        String access = loginForAccessToken();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/auth/refresh"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + access + "\"}"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
    }

    @Test
    @Order(18)
    @DisplayName("Oversized bearer token (32KB) → clean 4xx, never 500")
    void oversizedAuthHeader4xx() throws Exception {
        String giant = "x".repeat(32 * 1024) + ".y.z";
        int status = me(giant).statusCode();
        assertTrue(status == 400 || status == 401,
                "oversized bearer must be rejected cleanly (400 header-limit or 401), got " + status);
    }

    @Test
    @Order(19)
    @DisplayName("Valid login end-to-end (control: the suite attacks a WORKING plugin)")
    void validLoginControl() throws Exception {
        String access = loginForAccessToken();
        HttpResponse<String> resp = me(access);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("alice"));
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private HttpResponse<String> login(String username, String password) throws Exception {
        return loginJson("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private HttpResponse<String> loginJson(String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/auth/login"))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> me(String token) throws Exception {
        var b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/auth/me"))
                .GET().timeout(Duration.ofSeconds(5));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String loginForAccessToken() throws Exception {
        HttpResponse<String> resp = login("alice", "correct-horse");
        assertEquals(200, resp.statusCode(), "control login must work");
        String token = extract(resp.body(), "accessToken");
        assertNotNull(token, "login must return an accessToken");
        return token;
    }

    /** Crude JSON field extraction (test-only; avoids pulling a JSON lib for one field). */
    private static String extract(String json, String field) {
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** Builds a compact HS256 JWT for the given header/payload JSON with the given secret. */
    private static String jwt(String headerJson, String payloadJson, String secret) throws Exception {
        String signingInput = b64url(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + b64url(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signingInput + "." + b64url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    }
}
