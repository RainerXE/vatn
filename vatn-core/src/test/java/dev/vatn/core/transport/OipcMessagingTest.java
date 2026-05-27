package dev.vatn.core.transport;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class OipcMessagingTest {

    @Test
    public void testV3BinaryParsing() throws Exception {
        OipcMessagingTransport transport = new OipcMessagingTransport();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        transport.subscribe("binary.ingress", payload -> {
            receivedPayload.set(new String(payload));
            latch.countDown();
        });

        try (SocketChannel client = connectToTransport(transport)) {
            String testPayload = "binaryFastData";
            byte[] payloadData = testPayload.getBytes();
            byte[] nodeIdBytes = "test-client".getBytes();

            // 1. OIPC V2.12 HELLO handshake — V3 18-byte header + payload
            // Header: magic(4) + version(1) + flags(1) + len(4) + msgId(4) + seqIdx(4) = 18 bytes
            int helloLen = 3 + nodeIdBytes.length; // type(1) + major(1) + minor(1) + nodeId
            ByteBuffer hello = ByteBuffer.allocate(OipcMessagingTransport.V3_HEADER_SIZE + helloLen)
                .order(ByteOrder.BIG_ENDIAN);
            hello.put("OIPC".getBytes())
                 .put((byte) 3).put((byte) 0x22)           // version=3, flags=CONTROL|BINARY
                 .putInt(helloLen).putInt(123).putInt(0)    // len, msgId, seqIdx
                 .put((byte) 0x05).put((byte) 2).put((byte) 12)  // type=HELLO, major=2, minor=12
                 .put(nodeIdBytes);
            client.write(hello.flip());

            // 1.1 Read HELLO ACK
            CountDownLatch ackLatch = new CountDownLatch(1);
            ByteBuffer ack = ByteBuffer.allocate(OipcMessagingTransport.V3_HEADER_SIZE + 12);
            client.configureBlocking(false);
            Thread.ofVirtual().start(() -> {
                try {
                    long deadline = System.currentTimeMillis() + 1000;
                    while (ack.position() < OipcMessagingTransport.V3_HEADER_SIZE
                            && System.currentTimeMillis() < deadline) {
                        client.read(ack);
                    }
                } catch (java.io.IOException ignored) {
                } finally { ackLatch.countDown(); }
            });
            assertTrue(ackLatch.await(2, TimeUnit.SECONDS), "Timed out waiting for ACK");
            assertTrue(ack.position() >= OipcMessagingTransport.V3_HEADER_SIZE,
                "Should have received OIPC handshake ACK header");
            client.configureBlocking(true);

            // 2. V3 unchunked data frame: OIPC + 18-byte header + payload
            ByteBuffer frame = ByteBuffer.allocate(OipcMessagingTransport.V3_HEADER_SIZE + payloadData.length)
                .order(ByteOrder.BIG_ENDIAN);
            frame.put("OIPC".getBytes())
                 .put((byte) 3).put((byte) 0x02)              // version=3, MODE_BINARY
                 .putInt(payloadData.length).putInt(0).putInt(0) // len, msgId=0, seqIdx=0
                 .put(payloadData);
            client.write(frame.flip());

            boolean success = latch.await(2, TimeUnit.SECONDS);
            assertTrue(success, "Did not receive routed payload from V3 binary message");
            assertEquals(testPayload, receivedPayload.get());
        }
    }

    private SocketChannel connectToTransport(OipcMessagingTransport transport) throws Exception {
        if (transport.isUds()) {
            return SocketChannel.open(UnixDomainSocketAddress.of(transport.getConnectionPath()));
        } else {
            return SocketChannel.open(new InetSocketAddress("127.0.0.1", transport.getConnectionPort()));
        }
    }
}
