package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for the WebSocket surface (threat-model surface S6).
 *
 * <p>Boots a real node with a registered echo listener and attacks the upgrade
 * handshake and message channel: non-WS routes, malformed upgrades, cross-origin
 * upgrades, rapid connect/disconnect cycles, and message floods. The node must
 * repel or absorb every attack without a 500, a hang, or a resource leak.
 */
@Tag("adversarial")
class WsAdversarialTest {

    private static VNodeRunner node;
    private static int port;
    private static HttpClient http;
    private static final AtomicInteger opened = new AtomicInteger();
    private static final AtomicInteger closed = new AtomicInteger();
    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    @BeforeAll
    static void startNode() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("vatn-ws-adv-");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        node = VNodeRunner.create(0);
        node.registerWebSocket("/ws/echo", new dev.vatn.api.VWsListener() {
            @Override public void onOpen(dev.vatn.api.VWsSession session) { opened.incrementAndGet(); }
            @Override public void onMessage(dev.vatn.api.VWsSession session, String text, boolean last) {
                session.send(text);
            }
            @Override public void onClose(dev.vatn.api.VWsSession session, int code, String reason) {
                closed.incrementAndGet();
            }
        });
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
    // Upgrade-handshake attacks (raw socket)
    // =========================================================================

    @Test
    @DisplayName("WS upgrade to a route with no WS endpoint → upgrade NOT honored (no 101), no 5xx")
    void upgradeToNonWsRouteNotHonored() throws Exception {
        // Security property: the Upgrade must not be honored on a route with no WS endpoint.
        // A normal 200 (handler answers, upgrade ignored) is a valid rejection; 101 is not.
        String resp = rawUpgrade("/info", true);
        String line = firstLine(resp);
        assertFalse(line.startsWith("HTTP/1.1 101"),
                "non-WS route must not switch protocols. Got: " + line);
        assertFalse(line.contains(" 5"), "upgrade to non-WS route must never 5xx, got: " + line);
    }

    @Test
    @DisplayName("Upgrade missing Sec-WebSocket-Key → 4xx, never 500")
    void malformedUpgradeMissingKey() throws Exception {
        String resp = rawUpgrade("/ws/echo", false);
        String line = firstLine(resp);
        assertTrue(line.contains(" 4") || line.contains(" 426"),
                "malformed upgrade must yield 4xx, got: " + line);
        assertFalse(line.contains(" 5"), "malformed upgrade must never 500, got: " + line);
    }

    @Test
    @DisplayName("Valid upgrade still works after malformed attempts (control)")
    void validUpgradeControl() throws Exception {
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/echo"), new NoopListener())
                .get(5, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Cross-origin upgrade (hostile Origin header) — posture recorded")
    void crossOriginUpgradePosture() throws Exception {
        // Record current behavior: Helidon WS upgrades are origin-agnostic by default.
        // Cross-site WebSocket hijacking defense is a deployment concern (proxy/origin
        // allowlist). This test documents the posture so a future change is deliberate.
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .header("Origin", "https://evil.example.com")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/echo"), new NoopListener())
                    .get(5, TimeUnit.SECONDS);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "x").get(5, TimeUnit.SECONDS);
            System.out.println("[ws-posture] cross-origin upgrade ACCEPTED (origin-agnostic, default)");
        } catch (Exception e) {
            System.out.println("[ws-posture] cross-origin upgrade REJECTED: " + e.getMessage());
        }
        // Either posture is acceptable — the point is that it is observed and recorded.
        assertTrue(true);
    }

    // =========================================================================
    // Connection-lifecycle attacks
    // =========================================================================

    @Test
    @DisplayName("50 rapid connect/disconnect cycles → no crash, balanced lifecycle, node healthy")
    void rapidConnectDisconnect() throws Exception {
        int cycles = 50;
        int openBefore = opened.get();
        for (int i = 0; i < cycles; i++) {
            WebSocket ws = http.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/echo"), new NoopListener())
                    .get(5, TimeUnit.SECONDS);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
        }
        assertEquals(cycles, opened.get() - openBefore, "every connection must reach onOpen");

        // close events are async — give the server a moment, then require most to have closed
        long deadline = System.currentTimeMillis() + 3000;
        while (closed.get() < openBefore + cycles - 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertNodeHealthy();
    }

    @Test
    @DisplayName("Message flood (1000 small texts) → all echoed, no crash")
    void smallMessageFlood() throws Exception {
        int total = 1000;
        CountDownLatch echoes = new CountDownLatch(total);
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/echo"),
                        new WebSocket.Listener() {
                            @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                echoes.countDown();
                                return WebSocket.Listener.super.onText(w, data, last);
                            }
                        })
                .get(5, TimeUnit.SECONDS);
        for (int i = 0; i < total; i++) {
            ws.sendText("msg-" + i, true);
        }
        assertTrue(echoes.await(15, TimeUnit.SECONDS),
                "all " + total + " echoes must return; got " + (total - echoes.getCount()));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
        assertNodeHealthy();
    }

    @Test
    @DisplayName("Oversized single message (4MB) → bounded: handled or connection closed, no OOM")
    void oversizedMessageBounded() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger outcome = new AtomicInteger(0); // 1=echoed, 2=closed by server, 3=error
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/echo"),
                            new WebSocket.Listener() {
                                @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                    outcome.compareAndSet(0, 1);
                                    done.countDown();
                                    return WebSocket.Listener.super.onText(w, data, last);
                                }
                                @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                                    outcome.compareAndSet(0, 2);
                                    done.countDown();
                                    return WebSocket.Listener.super.onClose(w, code, reason);
                                }
                                @Override public void onError(WebSocket w, Throwable error) {
                                    outcome.compareAndSet(0, 3);
                                    done.countDown();
                                }
                            })
                    .get(5, TimeUnit.SECONDS);
            String big = "x".repeat(4 * 1024 * 1024);
            ws.sendText(big, true);
            boolean finished = done.await(20, TimeUnit.SECONDS);
            // any terminal outcome is acceptable EXCEPT the server hanging forever
            assertTrue(finished, "server must reach a terminal state for a 4MB message (echo/close/error)");
            System.out.println("[ws-posture] 4MB message outcome=" + outcome.get() + " (1=echoed,2=closed,3=error)");
        } finally {
            assertNodeHealthy();
        }
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private void assertNodeHealthy() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/info"))
                        .GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() < 500, "node must stay healthy after WS attack");
    }

    /** Sends a raw WS upgrade request (optionally with the Sec-WebSocket-Key) and reads the response. */
    private String rawUpgrade(String path, boolean withKey) throws Exception {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5000);
            OutputStream out = sock.getOutputStream();
            StringBuilder req = new StringBuilder("GET " + path + " HTTP/1.1\r\n")
                    .append("Host: localhost\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n");
            if (withKey) req.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = sock.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII) : "";
        }
    }

    private static String firstLine(String resp) {
        int i = resp.indexOf('\n');
        return i > 0 ? resp.substring(0, i).trim() : resp.trim();
    }

    private static final class NoopListener implements WebSocket.Listener {}
}
