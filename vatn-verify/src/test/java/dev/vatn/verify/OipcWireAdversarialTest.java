package dev.vatn.verify;

import dev.vatn.core.transport.OipcMessagingTransport;
import org.junit.jupiter.api.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial OIPC wire-level hardening tests against the real V3 "Relentless"
 * 18-byte binary format (docs/oipc-protocol.md §2.1, implemented by
 * {@code dev.vatn.core.transport.OipcMessagingTransport}):
 *
 * <pre>
 * [magic "OIPC":4][wire_version=3:1][mode_flags:1][payload_length:4][message_id:4][sequence_idx:4][payload:N]
 * </pre>
 *
 * <p>HELLO is a CONTROL frame (flags 0x22 = MASK_CONTROL|MASK_BINARY) with payload
 * {@code [0x05, major=2, minor, nodeId…]}. Server feedback frames (ACK/NACK) are a
 * V3 frame with flags 0x22 and a 12-byte control payload
 * {@code [type:1][controlId:4][reserved:7]} (type 0x01 = ACK, 0x02 = NACK).
 *
 * <p>Attacks covered:
 * <ul>
 *   <li>Invalid magic bytes ("XIPC") — connection must be closed</li>
 *   <li>Partial frame (half a header) then connection close — server must survive</li>
 *   <li>Claimed payload length exceeding MAX_MESSAGE_SIZE (256 MB)</li>
 *   <li>Unsupported wire version (≠ 3)</li>
 *   <li>Data frame sent before the HELLO handshake</li>
 *   <li>Illegal CONTROL|CHUNKED flag combination</li>
 *   <li>Replay of a HELLO handshake on an established connection</li>
 *   <li>Duplicate message IDs on concurrent chunked transfers</li>
 *   <li>Rapid connect/disconnect cycling</li>
 * </ul>
 *
 * <p>Note on reads: {@code SocketChannel.read()} ignores {@code SO_TIMEOUT} and can
 * block forever; all server→client reads here go through
 * {@code sc.socket().getInputStream()}, which honours {@code setSoTimeout}.
 */
@DisplayName("OIPC Wire Adversarial Tests")
@Tag("adversarial")
class OipcWireAdversarialTest {

    private static OipcMessagingTransport server;
    private static int port;

    @BeforeAll
    static void startServer() {
        System.setProperty("vatn.ipc.force_tcp", "true");
        server = new OipcMessagingTransport();
        port   = server.getConnectionPort();
    }

    @AfterAll
    static void stopServer() {
        server.close();
        System.clearProperty("vatn.ipc.force_tcp");
    }

    // ── OIPC V3 frame helpers ─────────────────────────────────────────────────

    private static final byte[] MAGIC = "OIPC".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 3;
    private static final byte FLAGS_CONTROL_BINARY = 0x22; // MASK_CONTROL 0x20 | MASK_BINARY 0x02
    private static final int  ACK = 0x01;

    private static final int HEADER_SIZE   = 18;
    private static final int FEEDBACK_SIZE = HEADER_SIZE + 12;

    /** Write one well-formed V3 frame. */
    private static void writeFrame(SocketChannel sc, byte flags, int msgId, int seqIdx,
                                   byte[] payload) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(18 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC).put(VERSION).put(flags)
           .putInt(payload.length).putInt(msgId).putInt(seqIdx)
           .put(payload);
        buf.flip();
        while (buf.hasRemaining()) sc.write(buf);
    }

    /** Send a well-formed HELLO: CONTROL frame, payload [0x05, major=2, minor=12, nodeId]. */
    private static void sendHello(SocketChannel sc, String nodeId) throws Exception {
        byte[] nodeBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[3 + nodeBytes.length];
        payload[0] = 0x05; payload[1] = 2; payload[2] = 12;
        System.arraycopy(nodeBytes, 0, payload, 3, nodeBytes.length);
        writeFrame(sc, FLAGS_CONTROL_BINARY, 1, 0, payload);
    }

    // ── Server response helpers ───────────────────────────────────────────────

    /** Parsed server feedback frame (ACK/NACK). */
    private record Feedback(int type, long msgId, int controlId) {}

    /**
     * Read and parse one server feedback frame:
     * V3 header (flags 0x22, length 12) + control payload [type:1][controlId:4][reserved:7].
     */
    private static Feedback readFeedback(SocketChannel sc, int timeoutMs) throws IOException {
        sc.socket().setSoTimeout(timeoutMs);
        byte[] frame = readNBytes(sc.socket().getInputStream(), FEEDBACK_SIZE);
        ByteBuffer bb = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        assertArrayEquals(MAGIC, magic, "feedback magic");
        assertEquals(VERSION, bb.get(), "feedback wire version");
        assertEquals(FLAGS_CONTROL_BINARY, bb.get(), "feedback flags");
        assertEquals(12, bb.getInt(), "feedback control payload length");
        long msgId = bb.getInt() & 0xFFFFFFFFL;
        bb.getInt(); // sequence_idx — always 0 in feedback frames
        int type = bb.get() & 0xFF;
        int controlId = bb.getInt();
        bb.position(bb.position() + 7); // reserved
        return new Feedback(type, msgId, controlId);
    }

    private static byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("EOF after " + off + " of " + n + " bytes");
            off += r;
        }
        return buf;
    }

    /**
     * Contract after a protocol violation: the server abandons the connection and must
     * not process or answer further frames on it. The current implementation breaks out
     * of the read loop without closing the socket, so the expected signal is a read
     * timeout; an orderly EOF/reset satisfies the contract as well. Receiving actual
     * response data always fails.
     */
    private static void expectDropped(SocketChannel sc, int timeoutMs) throws IOException {
        sc.socket().setSoTimeout(timeoutMs);
        try {
            int r = sc.socket().getInputStream().read();
            assertEquals(-1, r, "server must not answer frames sent after a protocol violation");
        } catch (SocketTimeoutException expected) {
            // connection abandoned without close — current drop semantics
        } catch (SocketException reset) {
            // connection reset — also a valid drop
        }
    }

    /** The server must actively close this connection (EOF or RST — not hang). */
    private static void expectClosed(SocketChannel sc, int timeoutMs) throws IOException {
        sc.socket().setSoTimeout(timeoutMs);
        try {
            int r = sc.socket().getInputStream().read();
            assertEquals(-1, r, "server must close the connection, not send data");
        } catch (SocketException reset) {
            // connection reset by peer — a valid close
        } catch (SocketTimeoutException e) {
            fail("server must close the connection, but the read timed out");
        }
    }

    /** The connection must stay open and silent (no response within the timeout). */
    private static void expectSilent(SocketChannel sc, int timeoutMs) throws IOException {
        sc.socket().setSoTimeout(timeoutMs);
        try {
            int r = sc.socket().getInputStream().read();
            fail(r == -1
                    ? "server closed the connection — expected it to stay open"
                    : "server sent an unexpected response byte: " + r);
        } catch (SocketTimeoutException expected) {
            // silence on an open connection
        }
    }

    /** A fresh connection must complete a real HELLO and receive its ACK. */
    private static void serverIsAlive() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            sendHello(sc, "health-check-" + UUID.randomUUID());
            Feedback ack = readFeedback(sc, 3_000);
            assertEquals(ACK, ack.type(), "server must ACK a HELLO from a fresh connection");
        }
    }

    // =========================================================================
    // 1. Invalid magic bytes
    // =========================================================================

    @Test
    @DisplayName("Invalid magic bytes — server must close the connection and stay alive")
    @Timeout(10)
    void invalidMagicBytesRejected() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // A full, well-formed-looking HELLO frame — but with "XIPC" instead of "OIPC".
            byte[] nodeBytes = "evil-node".getBytes(StandardCharsets.UTF_8);
            ByteBuffer bad = ByteBuffer.allocate(18 + 3 + nodeBytes.length).order(ByteOrder.BIG_ENDIAN);
            bad.put("XIPC".getBytes(StandardCharsets.US_ASCII))
               .put(VERSION).put(FLAGS_CONTROL_BINARY)
               .putInt(3 + nodeBytes.length).putInt(1).putInt(0)
               .put((byte) 0x05).put((byte) 2).put((byte) 12).put(nodeBytes);
            bad.flip();
            while (bad.hasRemaining()) sc.write(bad);

            // First byte != 0x4F → server closes immediately (handleClient).
            expectClosed(sc, 3_000);
        }
        // Server must still accept and handshake new connections.
        serverIsAlive();
    }

    // =========================================================================
    // 2. Partial frame, then close
    // =========================================================================

    @Test
    @DisplayName("Partial V3 header (10 of 18 bytes) then close — server must not crash")
    @Timeout(10)
    void partialFrameThenClose() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // "OIPC" + version + only 5 of the 13 remaining header bytes — then EOF.
            ByteBuffer half = ByteBuffer.allocate(10);
            half.put(MAGIC).put(VERSION);
            half.put(new byte[5]);
            half.flip();
            while (half.hasRemaining()) sc.write(half);
        } // close mid-header
        // Server must still accept and handshake new connections.
        serverIsAlive();
    }

    // =========================================================================
    // 3. Oversized claimed payload length (0x7FFFFFFF)
    // =========================================================================

    @Test
    @DisplayName("Header claiming 0x7FFFFFFF payload bytes must be dropped without allocation")
    @Timeout(10)
    void oversizedClaimedLengthRejected() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // Header claims ~2 GB — above MAX_MESSAGE_SIZE (256 MB). No payload follows.
            ByteBuffer hdr = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
            hdr.put(MAGIC).put(VERSION).put((byte) 0x02)
               .putInt(Integer.MAX_VALUE)
               .putInt(99).putInt(0);
            hdr.flip();
            while (hdr.hasRemaining()) sc.write(hdr);

            // Server must reject on the length field alone, before allocating anything.
            // A HELLO sent afterwards would normally be ACKed — on a dropped connection
            // it must not be. (The write may fail if the peer has already closed; that
            // too proves the drop.)
            try { sendHello(sc, "post-violation-" + UUID.randomUUID()); }
            catch (IOException ignored) {}
            expectDropped(sc, 2_000);
        }
        serverIsAlive();
    }

    // =========================================================================
    // 4. Unsupported wire version
    // =========================================================================

    @Test
    @DisplayName("Wire version 4 — connection dropped, server stays alive")
    @Timeout(10)
    void invalidWireVersionRejected() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // Well-formed HELLO frame, but wire version 4 — only 3 is accepted.
            byte[] nodeBytes = "v4-node".getBytes(StandardCharsets.UTF_8);
            ByteBuffer bad = ByteBuffer.allocate(18 + 3 + nodeBytes.length).order(ByteOrder.BIG_ENDIAN);
            bad.put(MAGIC).put((byte) 4).put(FLAGS_CONTROL_BINARY)
               .putInt(3 + nodeBytes.length).putInt(1).putInt(0)
               .put((byte) 0x05).put((byte) 2).put((byte) 12).put(nodeBytes);
            bad.flip();
            while (bad.hasRemaining()) sc.write(bad);

            try { sendHello(sc, "post-violation-" + UUID.randomUUID()); }
            catch (IOException ignored) {}
            expectDropped(sc, 2_000);
        }
        serverIsAlive();
    }

    // =========================================================================
    // 5. Data before HELLO
    // =========================================================================

    @Test
    @DisplayName("Data frame before HELLO handshake — connection dropped, server stays alive")
    @Timeout(10)
    void dataBeforeHelloRejected() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // Well-formed BINARY data frame — but no handshake has happened yet.
            writeFrame(sc, (byte) 0x02, 1, 0, "premature-data".getBytes(StandardCharsets.UTF_8));

            try { sendHello(sc, "post-violation-" + UUID.randomUUID()); }
            catch (IOException ignored) {}
            expectDropped(sc, 2_000);
        }
        serverIsAlive();
    }

    // =========================================================================
    // 6. Illegal CONTROL|CHUNKED flag combination
    // =========================================================================

    @Test
    @DisplayName("CONTROL|CHUNKED flag combination — connection dropped, server stays alive")
    @Timeout(10)
    void controlChunkedCombinationRejected() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            sendHello(sc, "flag-attacker-" + UUID.randomUUID());
            Feedback hello = readFeedback(sc, 3_000);
            assertEquals(ACK, hello.type(), "handshake must succeed before the attack");

            // Illegal combination (docs §2.2): CONTROL 0x20 | CHUNKED 0x08 | BINARY 0x02.
            writeFrame(sc, (byte) 0x2A, 2, 0, new byte[] { 0x05 });

            // A single-chunk message would normally be ACKed — on a dropped connection
            // it must not be.
            try { writeFrame(sc, (byte) 0x1A, 3, 0, "x".getBytes(StandardCharsets.UTF_8)); }
            catch (IOException ignored) {}
            expectDropped(sc, 2_000);
        }
        serverIsAlive();
    }

    // =========================================================================
    // 7. Replayed HELLO
    // =========================================================================

    @Test
    @DisplayName("Replayed HELLO on an established connection is ignored — connection stays usable")
    @Timeout(15)
    void replayedHelloIgnoredConnectionStaysUsable() throws Exception {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            sendHello(sc, "replay-node-" + UUID.randomUUID());
            Feedback hello = readFeedback(sc, 3_000);
            assertEquals(ACK, hello.type(), "initial handshake must be ACKed");

            // Replay the HELLO on the same connection.
            sendHello(sc, "replay-node-2-" + UUID.randomUUID());

            // Documents current behaviour: post-handshake, a CONTROL frame is routed to
            // handleControlFrame (a no-op) — the replayed HELLO is silently ignored, no
            // second ACK is sent, and the connection is NOT dropped.
            expectSilent(sc, 1_500);

            // The connection must still be fully usable: a complete chunked message
            // (BINARY|CHUNKED|LAST) must be reassembled and ACKed.
            int msgId = 777;
            writeFrame(sc, (byte) 0x1A, msgId, 0, "still-alive".getBytes(StandardCharsets.UTF_8));
            Feedback ack = readFeedback(sc, 3_000);
            assertEquals(ACK, ack.type(), "connection must remain usable after a replayed HELLO");
            assertEquals(msgId, ack.msgId(), "ACK must reference the chunked message id");
        }
    }

    // =========================================================================
    // 8. Duplicate message IDs on concurrent connections
    // =========================================================================

    @Test
    @DisplayName("Same message ID on two concurrent connections must reassemble independently")
    @Timeout(15)
    void duplicateMessageIdsNoContamination() throws Exception {
        final int msgId = 4242;
        byte[] a0 = ("A0-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        byte[] a1 = "-A1".getBytes(StandardCharsets.UTF_8);
        byte[] b0 = ("B0-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        byte[] b1 = "-B1".getBytes(StandardCharsets.UTF_8);

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        server.subscribe("binary.ingress", payload -> {
            received.add(new String(payload, StandardCharsets.UTF_8));
            latch.countDown();
        });

        try (SocketChannel scA = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
             SocketChannel scB = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {

            sendHello(scA, "client-a-" + UUID.randomUUID());
            sendHello(scB, "client-b-" + UUID.randomUUID());
            assertEquals(ACK, readFeedback(scA, 3_000).type(), "client A handshake must be ACKed");
            assertEquals(ACK, readFeedback(scB, 3_000).type(), "client B handshake must be ACKed");

            // Interleave chunks of two different messages sharing the SAME message id.
            // Reassembly is keyed channelHash:msgId, so the two streams must not mix.
            writeFrame(scA, (byte) 0x0A, msgId, 0, a0);  // BINARY|CHUNKED, seq 0
            writeFrame(scB, (byte) 0x0A, msgId, 0, b0);
            writeFrame(scA, (byte) 0x1A, msgId, 1, a1);  // BINARY|CHUNKED|LAST, seq 1
            writeFrame(scB, (byte) 0x1A, msgId, 1, b1);

            // Each connection must receive its own ACK referencing msgId 4242.
            Feedback ackA = readFeedback(scA, 3_000);
            Feedback ackB = readFeedback(scB, 3_000);
            assertEquals(ACK, ackA.type(), "connection A must be ACKed");
            assertEquals(msgId, ackA.msgId(), "ACK on A must reference the message id");
            assertEquals(ACK, ackB.type(), "connection B must be ACKed");
            assertEquals(msgId, ackB.msgId(), "ACK on B must reference the message id");
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "both reassembled messages must be dispatched");
        String expectedA = new String(a0, StandardCharsets.UTF_8) + new String(a1, StandardCharsets.UTF_8);
        String expectedB = new String(b0, StandardCharsets.UTF_8) + new String(b1, StandardCharsets.UTF_8);
        assertTrue(received.contains(expectedA),
                "connection A message must reassemble intact, got: " + received);
        assertTrue(received.contains(expectedB),
                "connection B message must reassemble intact, got: " + received);
    }

    // =========================================================================
    // 9. Rapid connect/disconnect cycling
    // =========================================================================

    @Test
    @DisplayName("50 rapid connect/disconnect cycles must not crash or leak the accept loop")
    @Timeout(30)
    void rapidConnectDisconnectNoCrash() throws Exception {
        for (int i = 0; i < 50; i++) {
            try (SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
                // Immediately close — no data sent.
            }
        }
        serverIsAlive();
    }
}
