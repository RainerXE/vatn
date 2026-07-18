package dev.vatn.core.transport;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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

    @Test
    public void testV213GreetingThenHello() throws Exception {
        OipcMessagingTransport transport = new OipcMessagingTransport();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        transport.subscribe("binary.ingress", p -> { received.set(new String(p)); latch.countDown(); });

        try (SocketChannel client = connectToTransport(transport)) {
            byte[] greeting = buildGreeting("cli-1", new byte[24], 0x02);
            client.write(ByteBuffer.wrap(greeting));

            sendHelloThenData(client, "greetingData");

            assertTrue(latch.await(2, TimeUnit.SECONDS),
                "Did not receive routed payload after v2.13 Greeting + HELLO");
            assertEquals("greetingData", received.get());
        }
    }

    @Test
    public void testAuthTokenRejected() throws Exception {
        String prevRequire = System.getProperty("vatn.ipc.require_auth_token");
        String prevToken = System.getProperty("vatn.ipc.auth_token");
        String prevForceTcp = System.getProperty("vatn.ipc.force_tcp");
        System.setProperty("vatn.ipc.require_auth_token", "true");
        System.setProperty("vatn.ipc.auth_token", "secret123");
        System.setProperty("vatn.ipc.force_tcp", "true"); // auth only enforced on non-UDS
        try {
            OipcMessagingTransport transport = new OipcMessagingTransport();

            CountDownLatch latch = new CountDownLatch(1);
            transport.subscribe("binary.ingress", p -> latch.countDown());

            try (SocketChannel client = connectToTransport(transport)) {
                byte[] wrongToken = new byte[24];
                byte[] wrong = "wrong-token".getBytes(StandardCharsets.UTF_8);
                System.arraycopy(wrong, 0, wrongToken, 0, wrong.length);

                byte[] greeting = buildGreeting("cli-1", wrongToken, 0x02);
                client.write(ByteBuffer.wrap(greeting));

                sendHelloThenData(client, "shouldNotArrive");

                assertFalse(latch.await(1, TimeUnit.SECONDS),
                    "Payload must NOT be received when auth_token is wrong");
            }
        } finally {
            restoreProp("vatn.ipc.require_auth_token", prevRequire);
            restoreProp("vatn.ipc.auth_token", prevToken);
            restoreProp("vatn.ipc.force_tcp", prevForceTcp);
        }
    }

    @Test
    public void testHttpConnectTunnel() throws Exception {
        String prevEnabled = System.getProperty("vatn.ipc.http_connect_enabled");
        String prevForceTcp = System.getProperty("vatn.ipc.force_tcp");
        System.setProperty("vatn.ipc.http_connect_enabled", "true");
        System.setProperty("vatn.ipc.force_tcp", "true");
        try {
            OipcMessagingTransport transport = new OipcMessagingTransport();

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> received = new AtomicReference<>();
            transport.subscribe("binary.ingress", p -> { received.set(new String(p)); latch.countDown(); });

            try (java.net.Socket client = rawSocket(transport)) {
                int port = transport.getConnectionPort();
                String connectReq = "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n\r\n";
                client.getOutputStream().write(connectReq.getBytes(StandardCharsets.US_ASCII));

                // Read the 200 response line.
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String statusLine = br.readLine();
                assertNotNull(statusLine, "Expected an HTTP response line");
                assertTrue(statusLine.contains("200 Connection established"),
                    "Expected 200 Connection established, got: " + statusLine);

                // The socket is now a transparent pipe — send a normal 'O' + V3 HELLO + data frame.
                sendHelloThenData(client.getOutputStream(), "tunnelData");

                assertTrue(latch.await(2, TimeUnit.SECONDS),
                    "Did not receive routed payload over HTTP CONNECT tunnel");
                assertEquals("tunnelData", received.get());
            }
        } finally {
            restoreProp("vatn.ipc.http_connect_enabled", prevEnabled);
            restoreProp("vatn.ipc.force_tcp", prevForceTcp);
        }
    }

    @Test
    public void testHttpConnectDenied() throws Exception {
        String prevEnabled = System.getProperty("vatn.ipc.http_connect_enabled");
        String prevAllowlist = System.getProperty("vatn.ipc.connect_allowlist");
        String prevForceTcp = System.getProperty("vatn.ipc.force_tcp");
        System.setProperty("vatn.ipc.http_connect_enabled", "true");
        System.setProperty("vatn.ipc.connect_allowlist", "10.0.0.1:443"); // different host
        System.setProperty("vatn.ipc.force_tcp", "true");
        try {
            OipcMessagingTransport transport = new OipcMessagingTransport();

            CountDownLatch latch = new CountDownLatch(1);
            transport.subscribe("binary.ingress", p -> latch.countDown());

            try (java.net.Socket client = rawSocket(transport)) {
                int port = transport.getConnectionPort();
                String connectReq = "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n\r\n";
                client.getOutputStream().write(connectReq.getBytes(StandardCharsets.US_ASCII));

                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String statusLine = br.readLine();
                assertNotNull(statusLine, "Expected an HTTP response line");
                assertTrue(statusLine.contains("403 Forbidden"),
                    "Expected 403 Forbidden, got: " + statusLine);

                // Connection is closed on deny — no payload should arrive.
                assertFalse(latch.await(1, TimeUnit.SECONDS),
                    "Payload must NOT be received when CONNECT is denied");
            }
        } finally {
            restoreProp("vatn.ipc.http_connect_enabled", prevEnabled);
            restoreProp("vatn.ipc.connect_allowlist", prevAllowlist);
            restoreProp("vatn.ipc.force_tcp", prevForceTcp);
        }
    }

    private static java.net.Socket rawSocket(OipcMessagingTransport transport) throws Exception {
        java.net.Socket s = new java.net.Socket();
        s.connect(new InetSocketAddress("127.0.0.1", transport.getConnectionPort()));
        return s;
    }

    private static void restoreProp(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private byte[] buildGreeting(String clientId, byte[] token, int transport) {
        ByteBuffer bb = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("OIPC".getBytes(StandardCharsets.US_ASCII)); // 0..3 magic
        bb.put((byte) 2);                                    // 4 ver_major
        bb.put((byte) 12);                                   // 5 ver_minor
        bb.putShort((short) 0);                              // 6..7 flags u16 LE
        bb.putInt(0);                                        // 8..11 codec_pref u32 LE
        bb.put((byte) 0);                                    // 12 mode_flags
        bb.put((byte) 0);                                    // 13 channel_mode
        bb.put((byte) transport);                            // 14 transport
        bb.putInt(0);                                        // 15..18 session_hint u32 LE
        byte[] cid = new byte[16];
        byte[] cidSrc = clientId.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(cidSrc, 0, cid, 0, Math.min(cidSrc.length, 16));
        bb.put(cid);                                         // 19..34 client_id
        byte[] tok = new byte[24];
        System.arraycopy(token, 0, tok, 0, Math.min(token.length, 24));
        bb.put(tok);                                         // 35..58 auth_token
        bb.put(new byte[5]);                                 // 59..63 reserved
        return bb.array();
    }

    private void sendHelloThenData(SocketChannel client, String payload) throws Exception {
        byte[] payloadData = payload.getBytes(StandardCharsets.UTF_8);
        byte[] nodeIdBytes = "test-client".getBytes(StandardCharsets.UTF_8);

        int helloLen = 3 + nodeIdBytes.length;
        ByteBuffer hello = ByteBuffer.allocate(OipcMessagingTransport.V3_HEADER_SIZE + helloLen)
            .order(ByteOrder.BIG_ENDIAN);
        hello.put("OIPC".getBytes())
             .put((byte) 3).put((byte) 0x22)
             .putInt(helloLen).putInt(123).putInt(0)
             .put((byte) 0x05).put((byte) 2).put((byte) 12)
             .put(nodeIdBytes);
        client.write(hello.flip());

        ByteBuffer frame = ByteBuffer.allocate(OipcMessagingTransport.V3_HEADER_SIZE + payloadData.length)
            .order(ByteOrder.BIG_ENDIAN);
        frame.put("OIPC".getBytes())
             .put((byte) 3).put((byte) 0x02)
             .putInt(payloadData.length).putInt(0).putInt(0)
             .put(payloadData);
        client.write(frame.flip());
    }

    private void sendHelloThenData(java.io.OutputStream out, String payload) throws Exception {
        byte[] payloadData = payload.getBytes(StandardCharsets.UTF_8);
        byte[] nodeIdBytes = "test-client".getBytes(StandardCharsets.UTF_8);

        int helloLen = 3 + nodeIdBytes.length;
        ByteBuffer hello = ByteBuffer.allocate(OipcMessagingTransport.V3_HEADER_SIZE + helloLen)
            .order(ByteOrder.BIG_ENDIAN);
        hello.put("OIPC".getBytes())
             .put((byte) 3).put((byte) 0x22)
             .putInt(helloLen).putInt(123).putInt(0)
             .put((byte) 0x05).put((byte) 2).put((byte) 12)
             .put(nodeIdBytes);
        out.write(hello.array());
        out.flush();

        ByteBuffer frame = ByteBuffer.allocate(OipcMessagingTransport.V3_HEADER_SIZE + payloadData.length)
            .order(ByteOrder.BIG_ENDIAN);
        frame.put("OIPC".getBytes())
             .put((byte) 3).put((byte) 0x02)
             .putInt(payloadData.length).putInt(0).putInt(0)
             .put(payloadData);
        out.write(frame.array());
        out.flush();
    }

    private SocketChannel connectToTransport(OipcMessagingTransport transport) throws Exception {
        if (transport.isUds()) {
            return SocketChannel.open(UnixDomainSocketAddress.of(transport.getConnectionPath()));
        } else {
            return SocketChannel.open(new InetSocketAddress("127.0.0.1", transport.getConnectionPort()));
        }
    }
}
