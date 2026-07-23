package dev.vatn.verify;

import dev.vatn.core.transport.OipcMessagingTransport;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.*;
import org.junit.jupiter.api.Tag;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based fuzzing for the OIPC V3 frame parser (threat-model surface S2).
 *
 * <p>Sovereign invariant under test: <b>the parser only ever drops — it never hangs,
 * never throws uncaught, and stays alive for the next valid client.</b> Every property
 * ends with a liveness probe (a valid HELLO must receive an ACK).
 */
@Tag("adversarial")
class OipcFrameFuzzTest {

    private static OipcMessagingTransport server;
    private static int port;

    @BeforeContainer
    static void startServer() {
        System.setProperty("vatn.ipc.force_tcp", "true");
        server = new OipcMessagingTransport();
        port = server.getConnectionPort();
        if (port <= 0) {
            throw new RuntimeException("OIPC server must be bound to a valid port, got " + port);
        }
    }

    @AfterContainer
    static void stopServer() {
        if (server != null) server.close();
        System.clearProperty("vatn.ipc.force_tcp");
    }

    // =========================================================================
    // P1: arbitrary byte soup
    // =========================================================================

    @Property(tries = 120)
    void arbitraryBytesNeverHangServer(@ForAll byte[] junk) throws Exception {
        byte[] capped = cap(junk, 512);
        try (SocketChannel c = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            if (capped.length > 0) {
                ByteBuffer b = ByteBuffer.wrap(capped);
                while (b.hasRemaining()) c.write(b);
            }
        } // close immediately — server must deal with EOF at any parser state
        assertServerAlive("after arbitrary-bytes fuzz");
    }

    // =========================================================================
    // P2: structured V3 headers with fuzzed fields
    // =========================================================================

    @Property(tries = 120)
    void fuzzedV3HeadersNeverHangOrContaminate(
            @ForAll byte flags,
            @ForAll int claimedLength,
            @ForAll int msgId,
            @ForAll int seqIdx,
            @ForAll byte[] payload) throws Exception {

        byte[] capped = cap(payload, 1024);
        ByteBuffer frame = ByteBuffer.allocate(18 + capped.length).order(ByteOrder.BIG_ENDIAN);
        frame.put("OIPC".getBytes()).put((byte) 3).put(flags);
        frame.putInt(claimedLength).putInt(msgId).putInt(seqIdx);
        frame.put(capped);
        frame.flip();

        try (SocketChannel c = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            while (frame.hasRemaining()) c.write(frame);
            // Give the server a beat to process, then close: EOF must resolve any
            // readFully waiting on a bogus claimedLength.
            Thread.sleep(30);
        }
        assertServerAlive("after fuzzed header (flags=0x" + Integer.toHexString(flags & 0xFF)
                + ", claimedLength=" + claimedLength + ")");
    }

    // =========================================================================
    // P3: fuzzed v2.13 Greetings stay deadline-bounded
    // =========================================================================

    @Property(tries = 80)
    void fuzzedGreetingsBounded(@ForAll byte[] greetingBytes) throws Exception {
        byte[] capped = cap(greetingBytes, 128);
        long start = System.nanoTime();
        try (SocketChannel c = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // Force the Greeting discriminator: "OIPC" + ver_major=2, then fuzzed remainder
            ByteBuffer b = ByteBuffer.allocate(5 + capped.length);
            b.put("OIPC".getBytes()).put((byte) 2).put(capped);
            b.flip();
            while (b.hasRemaining()) c.write(b);
            // Hold the connection open briefly — a truncated greeting must die by the
            // 5s handshake deadline, not pin the thread.
            Thread.sleep(60);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 10_000,
                "fuzzed greeting handling must stay bounded, took " + elapsedMs + "ms");
        assertServerAlive("after fuzzed greeting");
    }

    // =========================================================================
    // invariant: server answers a valid v2.12 HELLO with an ACK
    // =========================================================================

    private void assertServerAlive(String context) throws Exception {
        try (SocketChannel c = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            byte[] nb = "fuzz-probe".getBytes(StandardCharsets.UTF_8);
            ByteBuffer hello = ByteBuffer.allocate(18 + 3 + nb.length).order(ByteOrder.BIG_ENDIAN);
            hello.put("OIPC".getBytes()).put((byte) 3).put((byte) 0x22);
            hello.putInt(3 + nb.length).putInt(0).putInt(0);
            hello.put((byte) 0x05).put((byte) 2).put((byte) 12).put(nb);
            hello.flip();
            while (hello.hasRemaining()) c.write(hello);

            ByteBuffer ack = ByteBuffer.allocate(30);
            long deadline = System.currentTimeMillis() + 3000;
            c.configureBlocking(false);
            try {
                int total = 0;
                while (total < 18 && System.currentTimeMillis() < deadline) {
                    int n = c.read(ack);
                    if (n > 0) total += n;
                    else if (n < 0) break;
                    else Thread.sleep(10);
                }
                assertTrue(total >= 18,
                        "server must answer a valid HELLO with an ACK " + context + " (got " + total + " bytes)");
            } finally {
                c.configureBlocking(true);
            }
        }
    }

    private static byte[] cap(byte[] in, int max) {
        if (in == null) return new byte[0];
        if (in.length <= max) return in;
        byte[] out = new byte[max];
        System.arraycopy(in, 0, out, 0, max);
        return out;
    }
}
