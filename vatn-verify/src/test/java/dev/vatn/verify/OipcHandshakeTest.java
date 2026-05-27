package dev.vatn.verify;

import dev.vatn.core.transport.OipcMessagingTransport;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OipcHandshakeTest {

    private static OipcMessagingTransport server;
    private static int port;

    @BeforeAll
    static void setup() {
        System.setProperty("vatn.ipc.force_tcp", "true");
        server = new OipcMessagingTransport();
        port = server.getConnectionPort();
    }

    @AfterAll
    static void teardown() {
        server.close();
        System.clearProperty("vatn.ipc.force_tcp");
    }

    @Test
    @Order(1)
    void testHandshakeSuccess() throws Exception {
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // Send Valid V2.12 HELLO
            sendHello(client, 2, 12, "test-node");
            
            // Receive ACK
            ByteBuffer ack = ByteBuffer.allocate(18 + 12).order(ByteOrder.BIG_ENDIAN);
            int read = client.read(ack);
            assertTrue(read >= 18, "Should receive ACK header");
            
            ack.flip();
            ack.position(18);
            byte type = ack.get();
            assertEquals(0x01, type, "Should be ACK type");
        }
    }

    @Test
    @Order(2)
    void testDataBeforeHandshakeRejection() throws Exception {
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // Send Data Frame immediately
            ByteBuffer data = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
            data.put("OIPC".getBytes()).put((byte)2).put((byte)0x02).putInt(0);
            data.flip();
            client.write(data);
            
            // Server should drop connection
            Thread.sleep(100);
            ByteBuffer dummy = ByteBuffer.allocate(1);
            int read = client.read(dummy);
            assertEquals(-1, read, "Server must close connection if data sent before HELLO");
        }
    }

    @Test
    @Order(3)
    void testVersionMismatchRejection() throws Exception {
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
            // Send Incompatible Major Version (V1.0)
            sendHello(client, 1, 0, "old-node");
            
            Thread.sleep(100);
            ByteBuffer dummy = ByteBuffer.allocate(1);
            int read = client.read(dummy);
            assertEquals(-1, read, "Server must close connection on version mismatch");
        }
    }

    private void sendHello(SocketChannel client, int major, int minor, String nodeId) throws IOException {
        byte[] nodeBytes = nodeId.getBytes();
        int len = 3 + nodeBytes.length;
        ByteBuffer hello = ByteBuffer.allocate(18 + len).order(ByteOrder.BIG_ENDIAN);
        hello.put("OIPC".getBytes()).put((byte)3).put((byte)0x22); // CONTROL | BINARY
        hello.putInt(len).putInt(0).putInt(0); // Length, MsgID, Seq
        hello.put((byte)0x05).put((byte)major).put((byte)minor).put(nodeBytes);
        hello.flip();
        while (hello.hasRemaining()) client.write(hello);
    }
}
