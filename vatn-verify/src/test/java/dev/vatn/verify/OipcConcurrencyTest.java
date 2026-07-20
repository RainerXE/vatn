package dev.vatn.verify;

import dev.vatn.core.transport.OipcMessagingTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-Concurrency & Topology Stress Test for OIPC V2.12.
 */
public class OipcConcurrencyTest {

    private static OipcMessagingTransport server;
    private static int port;
    private static String vatncliJar;

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("vatn.ipc.force_tcp", "true");
        server = new OipcMessagingTransport();
        port = server.getConnectionPort();
        vatncliJar = resolveCliJar();
    }

    @AfterAll
    static void teardown() {
        server.close();
        System.clearProperty("vatn.ipc.force_tcp");
    }

    @Test
    @Tag("integration")
    void testMultiClientInterleaving() throws Exception {
        int clientCount = 10; 
        int msgSize = 1024 * 1024 * 2; // 2MB
        int totalExpected = clientCount;

        CountDownLatch latch = new CountDownLatch(totalExpected);
        AtomicLong receivedCount = new AtomicLong();

        server.subscribe("binary.ingress", p -> {
            receivedCount.incrementAndGet();
            latch.countDown();
        });

        List<Process> processes = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-jar", vatncliJar, 
                    "benchmark", "--mode=client", "--protocol=BINARY", 
                    "--count=1", "--payload-size=" + msgSize, "--port=" + port
            );
            processes.add(pb.start());
        }

        boolean done = latch.await(60, TimeUnit.SECONDS);
        for (Process p : processes) {
            if (p.isAlive()) p.destroyForcibly();
        }

        assertTrue(done, "Timed out waiting for interleaved reassemblies. Received: " + receivedCount.get());
        assertEquals(totalExpected, receivedCount.get(), "Should receive all interleaved messages perfectly.");
    }

    @Test
    @Tag("integration")
    void testBroadcastFanOut() throws Exception {
        int clientCount = 5;
        AtomicLong totalReceivedSinks = new AtomicLong();

        List<SocketChannel> clients = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            SocketChannel sc = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            sc.configureBlocking(true);
            sendHello(sc, "client-" + UUID.randomUUID());
            readFully(sc, ByteBuffer.allocate(18 + 12)); // ACK
            clients.add(sc);
        }

        for (SocketChannel sc : clients) {
            Thread.ofVirtual().start(() -> {
                try {
                    // V3 18-byte header: magic(0-3) version(4) flags(5) length(6-9) msgId(10-13) seq(14-17)
                    ByteBuffer header = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
                    while (true) {
                        header.clear();
                        readFully(sc, header);
                        header.flip();
                        header.position(5); // flags at 5, length at 6-9
                        byte flags = header.get();
                        int len = header.getInt();
                        ByteBuffer payload = ByteBuffer.allocate(len);
                        readFully(sc, payload);
                        totalReceivedSinks.incrementAndGet();
                    }
                } catch (Exception ignore) {}
            });
        }

        Thread.sleep(500); 
        server.publish("broadcast.test", "Hello Swarm".getBytes());
        
        Thread.sleep(1000); 
        assertEquals(clientCount, totalReceivedSinks.get(), "All connected clients should receive the broadcast.");

        for (SocketChannel sc : clients) sc.close();
    }

    @Test
    void testInvalidFlagCombinations() throws Exception {
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            sendHello(client, "malicious-actor");
            readFully(client, ByteBuffer.allocate(18 + 12));

            ByteBuffer badFrame = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
            badFrame.put("OIPC".getBytes()).put((byte)3).put((byte)(0x20 | 0x08)); // CONTROL | CHUNKED
            badFrame.putInt(0).putInt(1234).putInt(0);
            badFrame.flip();
            client.write(badFrame);

            Thread.sleep(100);
            ByteBuffer dummy = ByteBuffer.allocate(1);
            int read = client.read(dummy);
            assertEquals(-1, read, "Server must drop connection on contradictory protocol flags.");
        }
    }

    private void sendHello(SocketChannel sc, String nodeId) throws Exception {
        byte[] nb = nodeId.getBytes();
        ByteBuffer b = ByteBuffer.allocate(18 + 3 + nb.length).order(ByteOrder.BIG_ENDIAN);
        b.put("OIPC".getBytes()).put((byte)3).put((byte)0x22); // BINARY | CONTROL
        b.putInt(3 + nb.length).putInt(0).putInt(0);
        b.put((byte)0x05).put((byte)2).put((byte)12).put(nb);
        b.flip();
        while (b.hasRemaining()) sc.write(b);
    }

    private void readFully(SocketChannel sc, ByteBuffer buf) throws Exception {
        while (buf.hasRemaining()) {
            if (sc.read(buf) < 0) throw new Exception("EOF");
        }
    }

    private static String resolveCliJar() {
        Path target = Path.of("../vatn-cli/target");
        if (!Files.exists(target)) target = Path.of("vatn-system/vatn-cli/target");
        File[] jars = target.toFile().listFiles((d, n) -> n.startsWith("vatn-cli") && n.endsWith(".jar") && !n.contains("original"));
        return (jars != null && jars.length > 0) ? jars[0].getAbsolutePath() : "";
    }
}
