package dev.vatn.verify;

import dev.vatn.core.transport.OipcMessagingTransport;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for OIPC v2.13 auth_token enforcement and the HTTP CONNECT tunnel
 * (threat-model surfaces S3, S4, S5).
 *
 * <p>Each case boots a fresh {@link OipcMessagingTransport} with the relevant system
 * properties and attacks it over raw TCP: wrong/missing tokens, legacy-protocol auth
 * bypass, CONNECT allowlist bypass attempts, malformed and oversized CONNECT requests.
 */
@Tag("adversarial")
class OipcAuthAdversarialTest {

    private static final String TOKEN = "s3cr3t-token";
    private OipcMessagingTransport server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        System.clearProperty("vatn.ipc.force_tcp");
        System.clearProperty("vatn.ipc.require_auth_token");
        System.clearProperty("vatn.ipc.auth_token");
        System.clearProperty("vatn.ipc.http_connect_enabled");
        System.clearProperty("vatn.ipc.connect_allowlist");
    }

    private void startTcpServer() {
        System.setProperty("vatn.ipc.force_tcp", "true");
        server = new OipcMessagingTransport();
        port = server.getConnectionPort();
    }

    private SocketChannel connect() throws Exception {
        return SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
    }

    // =========================================================================
    // auth_token enforcement
    // =========================================================================

    @Test
    @DisplayName("v2.13 Greeting with WRONG auth_token → dropped before HELLO (no ACK)")
    void wrongTokenDroppedBeforeHello() throws Exception {
        System.setProperty("vatn.ipc.require_auth_token", "true");
        System.setProperty("vatn.ipc.auth_token", TOKEN);
        startTcpServer();

        try (SocketChannel c = connect()) {
            sendGreeting(c, "wrong-token-value");
            sendHello(c, "attacker");
            // server must have closed the connection: the next read is EOF (-1)
            assertTrue(readWithTimeout(c, 3000) < 0,
                    "connection must be closed on auth_token mismatch");
        }
    }

    @Test
    @DisplayName("v2.13 Greeting with CORRECT auth_token → handshake completes (ACK)")
    void correctTokenAccepted() throws Exception {
        System.setProperty("vatn.ipc.require_auth_token", "true");
        System.setProperty("vatn.ipc.auth_token", TOKEN);
        startTcpServer();

        try (SocketChannel c = connect()) {
            sendGreeting(c, TOKEN);
            sendHello(c, "friend");
            int n = readWithTimeout(c, 3000);
            assertTrue(n >= 18, "expected an ACK frame after valid token, got " + n + " bytes");
        }
    }

    @Test
    @DisplayName("FINDING: legacy v2.12 client bypasses require_auth_token — must be rejected on TCP")
    void legacyClientCannotBypassAuth() throws Exception {
        System.setProperty("vatn.ipc.require_auth_token", "true");
        System.setProperty("vatn.ipc.auth_token", TOKEN);
        startTcpServer();

        try (SocketChannel c = connect()) {
            // v2.12 clients never send a Greeting — they cannot present a token at all.
            // When the operator REQUIRES auth on TCP, such clients must be refused.
            sendHello(c, "legacy-attacker");
            assertTrue(readWithTimeout(c, 3000) < 0,
                    "legacy v2.12 connection must be refused when require_auth_token=true (TCP)");
        }
    }

    @Test
    @DisplayName("Control: legacy v2.12 client accepted when auth NOT required (backward compat)")
    void legacyClientAcceptedWhenAuthOff() throws Exception {
        startTcpServer(); // no require_auth_token
        try (SocketChannel c = connect()) {
            sendHello(c, "legacy-friend");
            int n = readWithTimeout(c, 3000);
            assertTrue(n >= 18, "legacy v2.12 handshake must still work when auth is off");
        }
    }

    @Test
    @DisplayName("UDS: require_auth_token is skipped by design (filesystem trust) — v2.12 works")
    void udsSkipsAuthByDesign() throws Exception {
        // No force_tcp → UDS transport. Auth is intentionally not enforced on UDS.
        System.setProperty("vatn.ipc.require_auth_token", "true");
        System.setProperty("vatn.ipc.auth_token", TOKEN);
        server = new OipcMessagingTransport();
        Assumptions.assumeTrue(server.isUds(), "UDS not available on this platform");

        try (SocketChannel c = SocketChannel.open(
                java.net.UnixDomainSocketAddress.of(server.getConnectionPath()))) {
            sendHello(c, "uds-legacy");
            int n = readWithTimeout(c, 3000);
            assertTrue(n >= 18, "UDS connection must complete the handshake (auth skipped by design)");
        }
    }

    // =========================================================================
    // HTTP CONNECT tunnel
    // =========================================================================

    @Test
    @DisplayName("CONNECT to non-allowlisted target → 403 + connection closed")
    void connectNonAllowlistedDenied() throws Exception {
        System.setProperty("vatn.ipc.http_connect_enabled", "true");
        System.setProperty("vatn.ipc.connect_allowlist", "good.host:443");
        startTcpServer();

        try (SocketChannel c = connect()) {
            write(c, "CONNECT evil.host:443 HTTP/1.1\r\nHost: evil.host\r\n\r\n");
            String resp = readAscii(c, 3000);
            assertTrue(resp.contains("403"), "expected 403 for non-allowlisted target, got: " + resp);
            // and the connection must then be closed
            assertTrue(readWithTimeout(c, 3000) < 0, "connection must be closed after 403");
        }
    }

    @Test
    @DisplayName("CONNECT to allowlisted target → 200, then normal v2.12 handshake through the tunnel")
    void connectAllowlistedAccepted() throws Exception {
        System.setProperty("vatn.ipc.http_connect_enabled", "true");
        System.setProperty("vatn.ipc.connect_allowlist", "good.host:443");
        startTcpServer();

        try (SocketChannel c = connect()) {
            write(c, "CONNECT good.host:443 HTTP/1.1\r\nHost: good.host\r\n\r\n");
            String resp = readAscii(c, 3000);
            assertTrue(resp.contains("200"), "expected 200 for allowlisted target, got: " + resp);
            // tunnel established — a normal v2.12 HELLO must now complete
            sendHello(c, "tunneled-client");
            int n = readWithTimeout(c, 3000);
            assertTrue(n >= 18, "expected ACK through the established tunnel");
        }
    }

    @Test
    @DisplayName("Malformed CONNECT (no parseable target) → closed")
    void connectMalformedClosed() throws Exception {
        System.setProperty("vatn.ipc.http_connect_enabled", "true");
        startTcpServer();

        try (SocketChannel c = connect()) {
            write(c, "CONNECT\r\n\r\n");
            assertTrue(readWithTimeout(c, 3000) < 0,
                    "malformed CONNECT request must be dropped");
        }
    }

    @Test
    @DisplayName("Oversized CONNECT headers (>8KB without terminator) → closed")
    void connectOversizedHeadersClosed() throws Exception {
        System.setProperty("vatn.ipc.http_connect_enabled", "true");
        startTcpServer();

        try (SocketChannel c = connect()) {
            StringBuilder junk = new StringBuilder("CONNECT good.host:443 HTTP/1.1\r\nX-Junk: ");
            junk.append("A".repeat(16 * 1024)); // no terminating CRLF — exceeds 8KB cap
            write(c, junk.toString());
            assertTrue(readWithTimeout(c, 3000) < 0,
                    "oversized CONNECT headers must be dropped");
        }
    }

    @Test
    @DisplayName("CONNECT disabled (default): first byte 'C' → unknown-magic close")
    void connectDisabledByDefault() throws Exception {
        startTcpServer(); // http_connect_enabled NOT set
        try (SocketChannel c = connect()) {
            write(c, "CONNECT good.host:443 HTTP/1.1\r\n\r\n");
            assertTrue(readWithTimeout(c, 3000) < 0,
                    "with the tunnel feature off, a CONNECT request must be rejected");
        }
    }

    // =========================================================================
    // wire helpers
    // =========================================================================

    /** Sends a v2.12 HELLO frame (V3 header, CONTROL|BINARY, type 0x05, version 2.12). */
    private static void sendHello(SocketChannel c, String nodeId) throws Exception {
        byte[] nb = nodeId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(18 + 3 + nb.length).order(ByteOrder.BIG_ENDIAN);
        b.put("OIPC".getBytes()).put((byte) 3).put((byte) 0x22);
        b.putInt(3 + nb.length).putInt(0).putInt(0);
        b.put((byte) 0x05).put((byte) 2).put((byte) 12).put(nb);
        b.flip();
        while (b.hasRemaining()) c.write(b);
    }

    /** Sends a v2.13 64-byte Greeting bootstrap with the given auth token. */
    private static void sendGreeting(SocketChannel c, String authToken) throws Exception {
        ByteBuffer g = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        g.put("OIPC".getBytes());          // 0-3 magic
        g.put((byte) 2);                   // 4 ver_major (Greeting discriminator)
        g.put((byte) 12);                  // 5 ver_minor
        g.putShort((short) 0);             // 6-7 flags
        g.putInt(0);                       // 8-11 codec_pref
        g.put((byte) 0);                   // 12 mode_flags
        g.put((byte) 0);                   // 13 channel_mode
        g.put((byte) 1);                   // 14 transport (TCP)
        g.putInt(0);                       // 15-18 session_hint
        byte[] clientId = "auth-adv-client".getBytes(StandardCharsets.UTF_8);
        g.put(clientId, 0, Math.min(clientId.length, 16)); // 19-34 client_id
        g.position(35);
        byte[] tok = authToken.getBytes(StandardCharsets.UTF_8);
        g.put(tok, 0, Math.min(tok.length, 24));           // 35-58 auth_token
        // 59-63 reserved (zero) — the full 64-byte frame MUST be sent regardless of field sizes
        g.position(64);
        g.flip();
        while (g.hasRemaining()) c.write(g);
    }

    private static void write(SocketChannel c, String ascii) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(ascii.getBytes(StandardCharsets.US_ASCII));
        while (b.hasRemaining()) c.write(b);
    }

    /** Reads up to 64 bytes with a deadline; returns byte count, or -1 on EOF/RST/timeout. */
    private static int readWithTimeout(SocketChannel c, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteBuffer buf = ByteBuffer.allocate(64);
        c.configureBlocking(false);
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    int n = c.read(buf);
                    if (n > 0) return n;
                    if (n < 0) return -1;
                } catch (java.net.SocketException rst) {
                    return -1; // "Connection reset" — server closed it, which is what we assert
                }
                Thread.sleep(20);
            }
            return -1; // treat timeout as "no data" — caller asserts on closure/response
        } finally {
            c.configureBlocking(true);
        }
    }

    /** Reads available bytes as ASCII within the deadline (for CONNECT responses). */
    private static String readAscii(SocketChannel c, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteBuffer buf = ByteBuffer.allocate(4096);
        c.configureBlocking(false);
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    int n = c.read(buf);
                    if (n > 0 || n < 0) break;
                } catch (java.net.SocketException rst) {
                    break;
                }
                Thread.sleep(20);
            }
        } finally {
            c.configureBlocking(true);
        }
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return new String(out, StandardCharsets.US_ASCII);
    }
}
