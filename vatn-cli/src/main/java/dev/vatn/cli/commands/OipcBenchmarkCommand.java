package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.Base64;
import java.util.concurrent.Callable;

/**
 * OIPC V2/V3 Benchmark Tool.
 */
@Command(
    name = "benchmark",
    description = "Run an ultra-high throughput OIPC Benchmark Suite.",
    mixinStandardHelpOptions = true
)
public class OipcBenchmarkCommand implements Callable<Integer> {

    public enum BenchmarkMode { client, server }
    public enum Protocol { BINARY, JSON_BASE64, JSON_NATIVE }

    @Option(names = "--mode", description = "Run as 'client' or 'server'.", defaultValue = "client")
    private BenchmarkMode mode;

    @Option(names = "--protocol", description = "Protocol to use: BINARY, JSON_BASE64, JSON_NATIVE", defaultValue = "BINARY")
    private Protocol protocol;

    @Option(names = "--path", description = "UDS socket path.")
    private String path;

    @Option(names = "--port", description = "TCP port (if --path empty).", defaultValue = "0")
    private int port;

    @Option(names = "--count", description = "Messages to send/receive.", defaultValue = "100000")
    private int count;

    @Option(names = "--payload-size", description = "Payload size in bytes.", defaultValue = "1024")
    private int payloadSize;

    private static final int CHUNK_SIZE = Integer.getInteger("vatn.ipc.chunk_size", 1024 * 1024);

    @Override
    public Integer call() throws Exception {
        return (mode == BenchmarkMode.server) ? runServer() : runClient();
    }

    private int runServer() throws Exception {
        System.out.printf("[OIPC-Bench] mode=server  protocol=%s  count=%d%n", protocol, count);
        if (path.isBlank() && port > 0) System.setProperty("vatn.ipc.force_tcp", "true");

        try (dev.vatn.core.transport.OipcMessagingTransport server =
                 new dev.vatn.core.transport.OipcMessagingTransport()) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(count);
            java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();

            server.subscribe("binary.ingress", payload -> {
                received.incrementAndGet();
                latch.countDown();
            });

            System.out.println("[OIPC-Bench] Server listening...");
            while (received.get() == 0) java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
            long startNs = System.nanoTime();

            boolean done = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            long elapsedNs = System.nanoTime() - startNs;

            double msgPerSec = received.get() / (elapsedNs / 1e9);
            double mbPerSec = (received.get() * (double) payloadSize) / (1024.0 * 1024.0) / (elapsedNs / 1e9);

            System.out.printf("[OIPC-Bench] DONE  received=%d  msg/s=%.0f  MB/s=%.2f%n",
                    received.get(), msgPerSec, mbPerSec);
            return done ? 0 : 1;
        }
    }

    private int runClient() throws Exception {
        System.out.printf("[OIPC-Bench] mode=client  protocol=%s  count=%d  payload=%d bytes%n", protocol, count, payloadSize);

        SocketChannel channel;
        if (!path.isBlank()) {
            channel = SocketChannel.open(UnixDomainSocketAddress.of(path));
        } else if (port > 0) {
            channel = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        } else {
            return 1;
        }

        byte[] payloadBytes = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) payloadBytes[i] = (byte)(i & 0xFF);

        long startNs = System.nanoTime();

        if (protocol == Protocol.BINARY) {
            // Mandatory OIPC V2.12 HELLO Handshake
            ByteBuffer hello = ByteBuffer.allocate(18 + 10).order(ByteOrder.BIG_ENDIAN);
            hello.put("OIPC".getBytes()).put((byte)3).put((byte)(0x02 | 0x20)); // V3, BINARY | CONTROL
            hello.putInt(10).putInt(0).putInt(0); // Length 10, MsgID 0, Seq 0
            hello.put((byte)0x05).put((byte)2).put((byte)12).put("BENCH".getBytes()); // Type HELLO, V2.12, NodeID
            hello.flip();
            while (hello.hasRemaining()) channel.write(hello);
            
            // Wait for ACK
            ByteBuffer ack = ByteBuffer.allocate(18 + 12);
            channel.read(ack);
            
            runBinaryClient(channel, payloadBytes);
        } else {
            runJsonClient(channel, payloadBytes);
        }

        long elapsedNs = System.nanoTime() - startNs;
        double msgPerSec = count / (elapsedNs / 1e9);
        double mbPerSec = (count * (double)payloadSize) / (1024.0 * 1024.0) / (elapsedNs / 1e9);

        System.out.printf("[OIPC-Bench] DONE  msg/s=%.0f  MB/s=%.2f%n", msgPerSec, mbPerSec);
        channel.close();
        return 0;
    }

    private void runBinaryClient(SocketChannel channel, byte[] payload) throws Exception {
        if (payload.length <= CHUNK_SIZE) {
            ByteBuffer env = ByteBuffer.allocateDirect(10 + payload.length).order(ByteOrder.BIG_ENDIAN);
            env.put("OIPC".getBytes()).put((byte)2).put((byte)0x02).putInt(payload.length).put(payload).flip();
            for (int i = 0; i < count; i++) {
                env.rewind();
                while (env.hasRemaining()) channel.write(env);
            }
        } else {
            // V3 Chunking
            int chunks = (int) Math.ceil((double) payload.length / CHUNK_SIZE);
            for (int i = 0; i < count; i++) {
                long msgId = i;
                for (int c = 0; c < chunks; c++) {
                    int off = c * CHUNK_SIZE;
                    int len = Math.min(CHUNK_SIZE, payload.length - off);
                    byte flags = (byte)(0x02 | 0x08 | (c == chunks - 1 ? 0x10 : 0));
                    ByteBuffer buf = ByteBuffer.allocate(18 + len).order(ByteOrder.BIG_ENDIAN);
                    buf.put("OIPC".getBytes()).put((byte)3).put(flags).putInt(len).putInt((int)msgId).putInt(c);
                    buf.put(payload, off, len).flip();
                    while (buf.hasRemaining()) channel.write(buf);
                }
            }
        }
    }

    private void runJsonClient(SocketChannel channel, byte[] payload) throws Exception {
        String data = (protocol == Protocol.JSON_BASE64) 
            ? Base64.getEncoder().encodeToString(payload)
            : new String(payload); // Raw string for native check (assuming valid-ish binary in test)

        String json = "{\"header\":{\"topic\":\"binary.ingress\"},\"payload\":\"" + data + "\"}\n";
        ByteBuffer buf = ByteBuffer.wrap(json.getBytes());
        
        for (int i = 0; i < count; i++) {
            buf.rewind();
            while (buf.hasRemaining()) channel.write(buf);
        }
    }
}
