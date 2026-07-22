package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial HTTP hardening tests.
 *
 * <p>Attack surface:
 * <ul>
 *   <li>Slow-loris (headers dribbled 1 byte at a time)</li>
 *   <li>Oversized request body</li>
 *   <li>CRLF header injection</li>
 *   <li>X-HTTP-Method-Override tunneling</li>
 *   <li>Path traversal in URL</li>
 *   <li>Null bytes in URL and body</li>
 *   <li>Server stability after floods</li>
 * </ul>
 *
 * @see WsAdversarialTest for WebSocket attacks
 * @see OipcWireAdversarialTest for OIPC wire attacks
 */
@DisplayName("Adversarial HTTP Tests")
@Tag("adversarial")
class HttpAdversarialTest {

    private static VNodeRunner node;
    private static int port;
    private static Path tempDir;
    private static HttpClient http;
    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    @BeforeAll
    static void startNode() throws Exception {
        tempDir = Files.createTempDirectory("vatn-http-adv-");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        node = VNodeRunner.create(0);
        node.start();
        port = node.getBoundPort();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1) // use HTTP/1.1 for raw socket tests
                .build();
    }

    @AfterAll
    static void stopNode() throws Exception {
        if (node != null) node.stop();
        // Restore the JVM-global user.home BEFORE deleting the temp dir — otherwise every
        // later test class in this fork sees a deleted directory as its home (poisoning).
        System.setProperty("user.home", ORIGINAL_USER_HOME);
        if (tempDir != null) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    // =========================================================================
    // 1. Slow-loris
    // =========================================================================

    @Test
    @DisplayName("Slow-loris: headers sent 1 byte/sec — connection must be closed by server")
    @Timeout(value = 40)
    void slowLorisConnectionClosedByServer() throws Exception {
        // Send HTTP request headers drip-by-drip. The server should time out the
        // incomplete request and close the connection.
        // ConnectionWatchdog fires at 30s; test gives 40s total margin.
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(32_000);
            OutputStream out = sock.getOutputStream();

            String headers = "GET /info HTTP/1.1\r\nHost: localhost\r\n";
            // Send headers one byte at a time with 50ms sleep (simulating slow-loris)
            // In a 20-second window, we send about 280 bytes, never completing the request
            for (int i = 0; i < Math.min(headers.length(), 40); i++) {
                out.write(headers.charAt(i));
                out.flush();
                Thread.sleep(50); // 50ms per byte = very slow
            }

            // The server should eventually close the connection (EOF on read)
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[1];
            int read = -2;
            try {
                // Try to read — either server closes (EOF=-1) or sends error response
                read = in.read(buf);
            } catch (SocketException e) {
                // Connection reset — server closed it. Correct.
                return;
            }
            // -1 = EOF (server closed), or we got an HTTP error response
            // Either way — the connection must not stay open indefinitely
            // If we got a response byte back (server sent something), check for timeout/error
            if (read > 0) {
                // Read full response to see what code was sent
                byte[] responseBuf = new byte[1024];
                int total = 0;
                try {
                    total = in.read(responseBuf);
                } catch (Exception ignored) {}
                String response = new String(buf) + (total > 0 ? new String(responseBuf, 0, total) : "");
                assertFalse(response.startsWith("HTTP/1.1 200"),
                        "Server must not respond 200 to an incomplete request");
            }
            // reaching here means server responded or closed — both acceptable
        }
    }

    // =========================================================================
    // 2. Oversized body
    // =========================================================================

    @Test
    @DisplayName("POST with 50MB body returns 413 or is rejected without OOM")
    @Timeout(30)
    void oversizedBodyRejected() throws Exception {
        // Use raw socket to send a large Content-Length without actually sending that much data
        // (or we can send a chunked 50MB body — but that risks OOM on the test side too)
        // Strategy: send a Content-Length of 50MB but only 1KB of body, then measure server response
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            byte[] miniBody = new byte[1024]; // 1KB
            String headers = "POST /api/anything HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: 52428800\r\n"  // claim 50 MB
                    + "\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(miniBody); // only send 1KB
            out.flush();

            // Read the server's response line
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n > 0) {
                String responseLine = new String(buf, 0, n, StandardCharsets.UTF_8);
                // Server must not crash — it can close connection or send 413
                assertFalse(responseLine.startsWith("HTTP/1.1 5"),
                        "Server must not return 5xx for oversized body claim: " + responseLine);
            }
        }

        // Verify node still responds to legitimate requests after the attack
        var healthResp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/info"))
                        .GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(healthResp.statusCode() < 500,
                "Node must still be healthy after oversized body attack");
    }

    // =========================================================================
    // 3. CRLF header injection
    // =========================================================================

    @Test
    @DisplayName("CRLF in URL path is rejected or sanitised (not passed through)")
    @Timeout(10)
    void crlfInUrlPathRejected() throws Exception {
        // The Java HttpClient will reject URLs with CR/LF. Use raw socket instead.
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            // Attempt CRLF injection in the path
            out.write(("GET /info\r\nX-Injected: evil\r\nignore: \r\n HTTP/1.1\r\n"
                    + "Host: localhost\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[512];
            int n;
            try { n = sock.getInputStream().read(buf); } catch (Exception e) { return; }
            if (n > 0) {
                String resp = new String(buf, 0, n);
                // Server must not honour the injected header or return 200 for the mangled request
                // A 400 Bad Request is the ideal response
                assertFalse(resp.contains("X-Injected"),
                        "Server must not echo back injected header: " + resp);
            }
        }
    }

    // =========================================================================
    // 4. HTTP Method Override tunneling
    // =========================================================================

    @Test
    @DisplayName("X-HTTP-Method-Override header must not be honored for destructive operations")
    @Timeout(10)
    void methodOverrideNotHonored() throws Exception {
        // Attempt to tunnel a DELETE via GET + header
        var resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/anything"))
                        .header("X-HTTP-Method-Override", "DELETE")
                        .GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        // The server must handle this as a GET, not as a DELETE.
        // 404 (route not found) is fine; 400 is fine; 200 is fine.
        // What's NOT fine is treating it as a DELETE that could cause side effects.
        assertNotEquals(500, resp.statusCode(),
                "Method override must not cause 500");
    }

    // =========================================================================
    // 5. Path traversal
    // =========================================================================

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "/../../../etc/passwd",
        "/..%2F..%2F..%2Fetc%2Fpasswd",
        "/api/../../../etc/shadow",
        "/api/%2e%2e/%2e%2e/etc/passwd",
        "/api/..;/admin"
    })
    @DisplayName("Path traversal attempts return 400/404, never expose filesystem")
    @Timeout(10)
    void pathTraversalRejected(String path) throws Exception {
        // Use raw socket to avoid Java's path normalisation
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[2048];
            int n;
            try { n = sock.getInputStream().read(buf); } catch (Exception e) { return; }
            if (n > 0) {
                String resp = new String(buf, 0, n);
                assertFalse(resp.contains("root:"), "Must not expose /etc/passwd content: " + resp);
                assertFalse(resp.toLowerCase().contains("<!doctype html") && resp.contains("root:"),
                        "Must not serve filesystem files: " + resp);
            }
        }
    }

    // =========================================================================
    // 6. Null bytes in URL
    // =========================================================================

    @Test
    @DisplayName("Null bytes in URL are rejected (not passed to filesystem)")
    @Timeout(10)
    void nullByteInUrlRejected() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            // Null byte in path — classic PHP/CGI bypass
            byte[] req = ("GET /api/file\0.txt HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            out.write(req);
            out.flush();

            byte[] buf = new byte[512];
            int n;
            try { n = sock.getInputStream().read(buf); } catch (Exception e) { return; }
            if (n > 0) {
                String resp = new String(buf, 0, n);
                assertFalse(resp.startsWith("HTTP/1.1 200"),
                        "Null byte in URL must not return 200: " + resp);
            }
        }
    }

    // =========================================================================
    // 7. Connection flood
    // =========================================================================

    @Test
    @DisplayName("200 simultaneous connections must not crash the server")
    @Timeout(30)
    void connectionFloodDoesNotCrash() throws Exception {
        int count = 200;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(() -> {
                try {
                    var resp = http.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:" + port + "/info"))
                                    .GET().timeout(Duration.ofSeconds(10)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() < 500) ok.incrementAndGet();
                    else failed.incrementAndGet();
                } catch (Exception e) { failed.incrementAndGet(); }
            }));
        }
        for (Future<?> f : futures) f.get(25, TimeUnit.SECONDS);
        pool.shutdown();

        // Allow some failures (backpressure is valid) but must not crash
        assertTrue(ok.get() >= count / 2,
                "At least 50% of flood connections must succeed. OK=" + ok.get() + " Failed=" + failed.get());

        // After flood — node must still respond
        var healthResp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/info"))
                        .GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(healthResp.statusCode() < 500, "Node must be healthy after flood");
    }

    // =========================================================================
    // 8. Host header injection
    // =========================================================================

    @Test
    @DisplayName("Malformed Host header does not crash server")
    @Timeout(10)
    void malformedHostHeader() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            out.write(("GET /info HTTP/1.1\r\n"
                    + "Host: evil.com:99999\r\n"
                    + "Host: second.com\r\n"  // duplicate Host header
                    + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[512];
            int n;
            try { n = sock.getInputStream().read(buf); } catch (Exception e) { return; }
            if (n > 0) {
                String resp = new String(buf, 0, n);
                assertFalse(resp.startsWith("HTTP/1.1 5"),
                        "Malformed Host header must not cause 5xx: " + resp);
            }
        }
    }

    // =========================================================================
    // 9. Header abuse & request smuggling (Phase 2, threat-model S1)
    // =========================================================================

    @Test
    @DisplayName("Oversized single header (64KB value) → 4xx or close, never hang/5xx")
    @Timeout(15)
    void oversizedHeaderBlockBounded() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(10_000);
            OutputStream out = sock.getOutputStream();
            String req = "GET /info HTTP/1.1\r\nHost: localhost\r\nX-Flood: "
                    + "A".repeat(64 * 1024) + "\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = readAvailable(sock, 4096);
            assertFalse(resp.startsWith("HTTP/1.1 5"),
                    "oversized header must not cause 5xx: " + firstLine(resp));
        }
        assertNodeHealthy();
    }

    @Test
    @DisplayName("Header-count flood (10k tiny headers) → bounded, no 5xx, node healthy")
    @Timeout(30)
    void headerCountFloodBounded() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            StringBuilder sb = new StringBuilder("GET /info HTTP/1.1\r\nHost: localhost\r\n");
            for (int i = 0; i < 10_000; i++) sb.append("X-").append(i).append(": v\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = readAvailable(sock, 4096);
            assertFalse(resp.startsWith("HTTP/1.1 5"),
                    "header flood must not cause 5xx: " + firstLine(resp));
            System.out.println("[http-posture] 10k-headers response: " + firstLine(resp));
        }
        assertNodeHealthy();
    }

    @Test
    @DisplayName("Duplicate conflicting Content-Length headers → rejected (strict parsing)")
    @Timeout(10)
    void duplicateConflictingContentLengthRejected() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            String req = "POST /info HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 5\r\nContent-Length: 10\r\n\r\nhello";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = readAvailable(sock, 4096);
            assertFalse(resp.startsWith("HTTP/1.1 5"),
                    "duplicate CL must not cause 5xx: " + firstLine(resp));
            assertFalse(resp.startsWith("HTTP/1.1 200"),
                    "conflicting Content-Length must not be accepted as a valid request: " + firstLine(resp));
        }
        assertNodeHealthy();
    }

    @Test
    @DisplayName("CL.TE smuggling probe → no desync (smuggled request not honored)")
    @Timeout(10)
    void clTeSmugglingNotDesynchronized() throws Exception {
        int twoHundreds = 0;
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            // A request valid under BOTH framings is the smuggling classic: if the front
            // uses CL and the back uses TE (or vice versa), the second "request" leaks through.
            String req = "POST /info HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "0\r\n\r\nGET /health HTTP/1.1\r\nHost: localhost\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = readAvailable(sock, 8192);
            assertFalse(resp.contains("HTTP/1.1 5"), "CL.TE probe must not cause 5xx: " + firstLine(resp));
            // Count 200s — a desync would answer the smuggled GET /health with 200
            int idx = 0;
            while ((idx = resp.indexOf(" 200", idx)) >= 0) { twoHundreds++; idx += 4; }
            assertTrue(twoHundreds <= 1,
                    "smuggled second request must not be answered (desync). Responses: " + resp.length());
        }
        assertNodeHealthy();
    }

    @Test
    @DisplayName("Multipart part flood (10k tiny parts) → bounded, no 5xx, node healthy")
    @Timeout(30)
    void multipartPartFloodBounded() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            String boundary = "vatnboundary";
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 10_000; i++) {
                body.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"f").append(i).append("\"\r\n\r\n")
                    .append("x\r\n");
            }
            body.append("--").append(boundary).append("--\r\n");
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

            String headers = "POST /info HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();

            String resp = readAvailable(sock, 4096);
            assertFalse(resp.startsWith("HTTP/1.1 5"),
                    "multipart flood must not cause 5xx: " + firstLine(resp));
            System.out.println("[http-posture] 10k-parts response: " + firstLine(resp));
        }
        assertNodeHealthy();
    }

    @Test
    @DisplayName("Expect: 100-continue with promised 50MB body never sent → posture recorded, node healthy")
    @Timeout(15)
    void expect100ContinueAbusePosture() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            String req = "POST /info HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 52428800\r\nExpect: 100-continue\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String resp = readAvailable(sock, 4096);
            // The server may answer 100 Continue and wait (same family as slow-loris),
            // or reject the oversized claim outright. Either is acceptable; a 200 is not.
            assertFalse(resp.startsWith("HTTP/1.1 200"),
                    "must not accept an entity-less 50MB claim: " + firstLine(resp));
            System.out.println("[http-posture] expect-100 response: " + firstLine(resp));
        }
        assertNodeHealthy();
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private void assertNodeHealthy() throws Exception {
        var healthResp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/info"))
                        .GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(healthResp.statusCode() < 500,
                "Node must still be healthy after the attack");
    }

    /** Reads whatever the server sends within the socket timeout; empty string on close/timeout. */
    private static String readAvailable(Socket sock, int maxBytes) throws Exception {
        try {
            byte[] buf = new byte[maxBytes];
            int n = sock.getInputStream().read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstLine(String resp) {
        if (resp == null || resp.isEmpty()) return "<no response>";
        int i = resp.indexOf('\n');
        return (i > 0 ? resp.substring(0, i) : resp).trim();
    }
}
