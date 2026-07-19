package dev.vatn.core.transport;

import dev.vatn.api.VMessaging;
import dev.vatn.api.VatnSecurity;
import dev.vatn.api.VTransport;
import dev.vatn.core.VJsonImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.channels.Channels;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * OIPC v2.12 / v2.13 Messaging Transport.
 * Wire format: V3 binary (18-byte header), optionally preceded by a v2.13 64-byte Greeting
 * bootstrap. UDS preferred, TCP loopback fallback. v2.12 clients (no Greeting) remain fully
 * backward compatible.
 */
public class OipcMessagingTransport implements VMessaging, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OipcMessagingTransport.class);

    private final Map<String, List<Consumer<byte[]>>> subscribers = new ConcurrentHashMap<>();
    private final List<ClientConnection> activeClients = new CopyOnWriteArrayList<>();

    private ServerSocketChannel serverSocketChannel;
    private boolean isUds = false;
    private String connectionPath;
    private int connectionPort;

    private final VJsonImpl json = new VJsonImpl();

    // Protocol Constants — V3 "Relentless" 18-byte header
    private static final int MASK_BINARY   = 0x02;
    private static final int MASK_CHUNKED  = 0x08;
    private static final int MASK_LAST     = 0x10;
    private static final int MASK_CONTROL  = 0x20;
    // V3 header: magic(4) + version(1) + flags(1) + payload_length(4) + message_id(4) + sequence_idx(4) = 18
    static final int V3_HEADER_SIZE = 18;

    // A client must complete its Greeting/bootstrap within this window or the connection is dropped.
    // Bounds the handshake phase so a peer that opens a socket but never finishes the bootstrap
    // (or sends a truncated/ambiguous frame) cannot pin a virtual thread indefinitely.
    private static final long HANDSHAKE_TIMEOUT_MS = Long.getLong("vatn.ipc.handshake_timeout_ms", 5_000);
    // Remaining header bytes after the version byte (flags + len + msgId + seqIdx = 1+4+4+4 = 13)
    private static final int V3_HEADER_AFTER_VERSION = 13;

    // OIPC v2.13 Greeting bootstrap — 64 bytes, little-endian for multi-byte fields.
    static final int GREETING_SIZE = 64;
    static final int AUTH_TOKEN_SIZE = 24;
    static final int CLIENT_ID_SIZE = 16;
    // Greeting_Flags bits
    private static final int GREETING_FLAG_TLS_REQUIRED         = 1 << 0;
    private static final int GREETING_FLAG_AUTH_REQUIRED        = 1 << 1;
    private static final int GREETING_FLAG_COMPRESSION_SUPPORTED = 1 << 2;
    static final int GREETING_FLAG_TUNNELED_HTTP               = 1 << 3;
    private static final int TRANSPORT_UDS = 0x01;
    private static final int TRANSPORT_TCP = 0x02;

    private static final int CHUNK_SIZE       = Integer.getInteger("vatn.ipc.chunk_size",       1024 * 1024);
    private static final int MAX_MESSAGE_SIZE = Integer.getInteger("vatn.ipc.max_message_size", 256 * 1024 * 1024);

    // Upper bound on the total bytes read for an HTTP CONNECT request (until the terminating blank
    // line). Guards against a malicious client streaming a never-terminating header.
    private static final int MAX_CONNECT_HEADER_BYTES = 8192;

    private static final java.util.regex.Pattern CONNECT_PATTERN =
        java.util.regex.Pattern.compile("^CONNECT\\s+([^:]+):(\\d+)\\s+HTTP/\\d\\.\\d$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final boolean httpConnectEnabled = Boolean.getBoolean("vatn.ipc.http_connect_enabled");
    private final String connectAllowlist = System.getProperty("vatn.ipc.connect_allowlist", "");

    private final Map<String, MessageReassembler> reassemblers = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService scheduler =
        java.util.concurrent.Executors.newScheduledThreadPool(1,
            r -> { Thread t = new Thread(r, "oipc-ttl-cleaner"); t.setDaemon(true); return t; });

    public OipcMessagingTransport() {
        startServer();
        scheduleTtlCleanup();
    }

    private void scheduleTtlCleanup() {
        long ttlMs = Long.getLong("vatn.ipc.reassembly_ttl_ms", 30000);
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            reassemblers.entrySet().removeIf(e -> now - e.getValue().lastUpdate > ttlMs);
        }, ttlMs, ttlMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void startServer() {
        if (Boolean.getBoolean("vatn.ipc.force_tcp")) {
            log.info("Forcing TCP mode via system property 'vatn.ipc.force_tcp'");
            bindTcp();
        } else {
            try {
                Path udsDir = Paths.get(System.getProperty("java.io.tmpdir"), "vatn");
                Files.createDirectories(udsDir);
                String udsPath = udsDir.resolve("vatn-" + UUID.randomUUID() + ".sock").toString();

                serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                serverSocketChannel.bind(UnixDomainSocketAddress.of(udsPath));
                isUds = true;
                connectionPath = udsPath;

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { Files.deleteIfExists(Paths.get(udsPath)); }
                    catch (IOException e) { log.warn("Failed to delete UDS socket file: {}", udsPath, e); }
                }));

                log.info("Initialized OipcMessagingTransport on UDS: {}", connectionPath);

            } catch (UnsupportedOperationException | IOException e) {
                log.warn("UDS binding failed, falling back to TCP Loopback: {}", e.getMessage());
                bindTcp();
            }
        }

        if (serverSocketChannel != null && serverSocketChannel.isOpen()) {
            Thread.ofVirtual().name("oipc-accept-loop").start(this::acceptLoop);
        }
    }

    private void bindTcp() {
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress("127.0.0.1", 0));
            InetSocketAddress bound = (InetSocketAddress) serverSocketChannel.getLocalAddress();
            connectionPort = bound.getPort();
            connectionPath = "127.0.0.1";
            isUds = false;
            log.info("Initialized OipcMessagingTransport on TCP Loopback: 127.0.0.1:{}", connectionPort);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to bind OIPC Transport to TCP", ex);
        }
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted() && serverSocketChannel.isOpen()) {
            try {
                SocketChannel clientChannel = serverSocketChannel.accept();
                String remoteAddr = clientChannel.getRemoteAddress().toString();

                if (!isTrusted(clientChannel)) {
                    log.warn("Blocked untrusted OIPC connection from {}", remoteAddr);
                    clientChannel.close();
                    continue;
                }

                ClientConnection client = new ClientConnection(clientChannel);
                activeClients.add(client);
                Thread.ofVirtual().start(() -> handleClient(client));
            } catch (IOException e) {
                if (serverSocketChannel.isOpen()) {
                    log.error("Error accepting OIPC client connection", e);
                }
            }
        }
    }

    private void handleClient(ClientConnection client) {
        try {
            PushbackInputStream in = new PushbackInputStream(
                Channels.newInputStream(client.channel), 1);
            int firstByte = in.read();

            if (firstByte == -1) { client.close(); return; }

            // Optional v2.13 HTTP CONNECT tunnel termination (TCP-only, gated by system property).
            if (firstByte == 'C' && httpConnectEnabled() && !isUds) {
                if (!handleConnectTunnel(in, client)) {
                    return; // deny: connection already closed by the handler
                }
                // On allow, the CONNECT exchange is consumed and the stream is now positioned
                // for the subsequent 'O' + Greeting/V3 frame. Re-read to dispatch normally.
                firstByte = in.read();
                if (firstByte == -1) { client.close(); return; }
            }

            if (firstByte != 0x4F) { // 'O'
                log.warn("Unknown OIPC magic byte 0x{} — expected 'O' (0x4F)",
                    Integer.toHexString(firstByte));
                client.close();
                return;
            }

            // Complete the magic: read 'IPC'.
            byte[] magicRest = new byte[3];
            if (readFully(in, magicRest, 3) < 3
                    || magicRest[0] != 'I' || magicRest[1] != 'P' || magicRest[2] != 'C') {
                log.warn("Lost framing sync at start — expected 'IPC'");
                client.close();
                return;
            }

            // Distinguish Greeting (byte[4] == ver_major 2) from a V3 frame (byte[4] == wire_version 3).
            int discriminator = in.read();
            if (discriminator == -1) { client.close(); return; }

            if (discriminator == 2) {          // v2.13 Greeting bootstrap
                // Read the remaining Greeting bytes under a deadline so a truncated/ambiguous
                // frame cannot pin the (virtual) thread (see readBounded / HANDSHAKE_TIMEOUT_MS).
                ByteBuffer gb = ByteBuffer.allocate(GREETING_SIZE - 5);
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MS);
                int got = readBounded(client.channel, gb, deadline);
                if (got < gb.capacity()) {
                    log.warn("Truncated OIPC v2.13 Greeting (got {} of {} bytes) — closing connection",
                        got, gb.capacity());
                    client.close();
                    return;
                }
                Greeting greeting = parseGreeting(new java.io.ByteArrayInputStream(gb.array()));
                if (greeting == null) { client.close(); return; }
                if (!validateAuthToken(greeting.authToken)) {
                    log.warn("OIPC v2.13 auth_token mismatch — closing connection before HELLO");
                    client.close();
                    return;
                }
                client.clientId = greeting.clientId;
                client.authToken = greeting.authToken;
                client.tunneledHttp = (greeting.flags & GREETING_FLAG_TUNNELED_HTTP) != 0;
                // Continue into the V3 loop; the next byte read will be the V3 wire_version.
                handleBinaryClient(client, in, -1);
            } else if (discriminator == 3) {   // legacy v2.12 client — this byte IS the V3 version
                handleBinaryClient(client, in, 3);
            } else {
                log.warn("Unsupported OIPC discriminator {} after magic — expected 2 (Greeting) or 3 (V3)",
                    discriminator);
                client.close();
            }
        } catch (IOException | RuntimeException e) {
            log.error("Client connection error", e);
            client.close();
        } finally {
            // The connection lifecycle is owned here: every return path above (protocol violation,
            // handshake reject, or normal loop exit) must end with the socket being torn down so a
            // peer can never block on a read against an open-but-abandoned channel.
            client.close();
            activeClients.remove(client);
            reassemblers.keySet().removeIf(k -> k.startsWith(client.id + ":"));
        }
    }

    private boolean isTrusted(SocketChannel channel) throws IOException {
        java.net.SocketAddress addr = channel.getRemoteAddress();
        if (!(addr instanceof java.net.InetSocketAddress)) return true; // UDS is inherently local
        String host = ((java.net.InetSocketAddress) addr).getAddress().getHostAddress();
        String trusted = System.getProperty("vatn.ipc.trusted_hosts", "127.0.0.1,0:0:0:0:0:0:0:1");
        for (String t : trusted.split(",")) {
            if (host.equals(t.trim())) return true;
        }
        return false;
    }

    private boolean httpConnectEnabled() {
        return httpConnectEnabled;
    }

    /**
     * Terminates an in-band HTTP CONNECT tunnel on a new TCP connection, then leaves the stream
     * positioned for the subsequent Greeting/V3 exchange. Returns {@code true} if the tunnel was
     * accepted (200 Connection established) and the caller should continue with the normal path,
     * or {@code false} if the request was denied (403) or malformed — in which case the connection
     * has already been closed.
     */
    private boolean handleConnectTunnel(PushbackInputStream in, ClientConnection client) throws IOException {
        StringBuilder request = new StringBuilder("C");
        int state = 0; // counts consecutive '\r\n' to detect the terminating blank line
        int totalBytes = 1; // the leading 'C' has already been read by the caller
        int b;
        String targetHostPort = null;
        while ((b = in.read()) != -1) {
            if (++totalBytes > MAX_CONNECT_HEADER_BYTES) {
                log.warn("HTTP CONNECT request exceeded {} bytes — closing connection",
                    MAX_CONNECT_HEADER_BYTES);
                client.close();
                return false;
            }
            if (b == '\r') {
                int c = in.read();
                if (c == '\n') {
                    if (state == 1) break; // second consecutive CRLF → end of headers
                    state = 1;
                    // capture the request line (first line only)
                    if (targetHostPort == null) {
                        targetHostPort = parseConnectTarget(request.toString().trim());
                    }
                    request.setLength(0);
                } else {
                    state = 0;
                    if (c != -1) in.unread(c);
                }
            } else {
                state = 0;
                request.append((char) b);
            }
        }
        if (targetHostPort == null) {
            log.warn("Malformed HTTP CONNECT request — closing connection");
            client.close();
            return false;
        }

        boolean allowed = connectAllowlist.isEmpty()
            || connectAllowlist.contains(targetHostPort);

        OutputStream out = Channels.newOutputStream(client.channel);
        if (allowed) {
            log.info("HTTP CONNECT tunnel authorized for {} (out-of-band, transparent)", targetHostPort);
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return true;
        } else {
            log.warn("HTTP CONNECT tunnel DENIED for {} — not in allowlist", targetHostPort);
            out.write("HTTP/1.1 403 Forbidden\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            client.close();
            return false;
        }
    }

    /**
     * Parses the target of a {@code CONNECT <host>:<port> HTTP/1.1} request line. Returns the
     * {@code host:port} string (lower-cased host) or {@code null} if the line is not a CONNECT.
     */
    private static String parseConnectTarget(String requestLine) {
        java.util.regex.Matcher m = CONNECT_PATTERN.matcher(requestLine);
        if (m.matches()) {
            return m.group(1).toLowerCase() + ":" + m.group(2);
        }
        return null;
    }

    /**
     * Runs the V3 frame loop. The 4-byte "OIPC" magic has already been consumed by the caller.
     *
     * @param preReadVersion the V3 wire_version byte already read from the stream (legacy path),
     *                       or {@code -1} if it still needs to be read (v2.13 Greeting path).
     */
    private void handleBinaryClient(ClientConnection client, InputStream in, int preReadVersion) throws IOException {
        byte[] frameMagic = new byte[4];
        boolean firstIteration = true;

        loop: while (true) {
            int version;
            if (firstIteration && preReadVersion != -1) {
                // Legacy path: the "OIPC" magic + this version byte were already consumed.
                version = preReadVersion;
            } else {
                if (firstIteration) {
                    // v2.13 Greeting path: the first V3 frame still carries its own "OIPC" magic.
                    int m = readFully(in, frameMagic, 4);
                    if (m < 4) break;
                    if (frameMagic[0] != 'O' || frameMagic[1] != 'I'
                            || frameMagic[2] != 'P' || frameMagic[3] != 'C') {
                        log.warn("Lost framing sync after Greeting — expected 'OIPC' prefix");
                        break loop;
                    }
                }
                version = in.read();
            }
            firstIteration = false;
            if (version == -1) break;

            if (version != 3) {
                log.warn("Unsupported OIPC wire version {} — only V3 is accepted", version);
                break loop;
            }

            // V3 header remainder: flags(1) + payload_length(4) + message_id(4) + sequence_idx(4) = 13 bytes
            byte[] hdr = new byte[V3_HEADER_AFTER_VERSION];
            if (readFully(in, hdr, V3_HEADER_AFTER_VERSION) < V3_HEADER_AFTER_VERSION) break loop;
            ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN);
            byte flags        = bb.get();
            int  payloadLength = bb.getInt();
            long msgId        = bb.getInt() & 0xFFFFFFFFL;
            int  seqIdx       = bb.getInt();

            if (payloadLength < 0 || payloadLength > MAX_MESSAGE_SIZE) {
                log.warn("OIPC payload length out of bounds: {}", payloadLength);
                break loop;
            }

            byte[] payload = new byte[payloadLength];
            if (readFully(in, payload, payloadLength) < payloadLength) break loop;

            if (!client.handshakeComplete) {
                if ((flags & MASK_CONTROL) != 0 && payload.length >= 3 && payload[0] == 0x05) {
                    handleHello(client, msgId, payload);
                } else {
                    log.warn("Connection dropped: data sent before HELLO handshake");
                    break loop;
                }
            } else if ((flags & MASK_CONTROL) != 0 && (flags & MASK_CHUNKED) != 0) {
                log.warn("Connection dropped: illegal flag combination CONTROL|CHUNKED");
                break loop;
            } else if ((flags & MASK_CONTROL) != 0) {
                handleControlFrame(payload);
            } else if ((flags & MASK_CHUNKED) != 0) {
                String key = client.channel.hashCode() + ":" + msgId;
                MessageReassembler ra = reassemblers.computeIfAbsent(key, k -> new MessageReassembler());
                if (ra.addChunk(seqIdx, payload)) {
                    if ((flags & MASK_LAST) != 0) {
                        dispatchToSubscribers(client, "binary.ingress", ra.assemble());
                        reassemblers.remove(key);
                        sendFeedback(client, (byte) 0x01, msgId, 0); // ACK
                    }
                } else {
                    log.warn("Sequence gap for MessageID {}. Sending NACK for index {}.", msgId, seqIdx);
                    sendFeedback(client, (byte) 0x02, msgId, seqIdx); // NACK
                }
            } else {
                dispatchToSubscribers(client, "binary.ingress", payload);
            }

            // Read next 'OIPC' magic prefix before looping.
            int prefixRead = readFully(in, frameMagic, 4);
            if (prefixRead < 4) break loop;
            if (frameMagic[0] != 'O' || frameMagic[1] != 'I' || frameMagic[2] != 'P' || frameMagic[3] != 'C') {
                log.warn("Lost framing sync — expected 'OIPC' prefix");
                break loop;
            }
        }
    }

    /**
     * Parses the remaining 60 bytes of a v2.13 Greeting. The 4-byte magic and the ver_major
     * byte (offset 4) have already been consumed. Returns {@code null} on truncation.
     */
    private Greeting parseGreeting(InputStream in) throws IOException {
        byte[] rest = new byte[GREETING_SIZE - 5]; // 59 bytes: offsets 5..63
        if (readFully(in, rest, rest.length) < rest.length) {
            log.warn("Truncated OIPC v2.13 Greeting");
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        bb.get();                       // 5 ver_minor (unused; wire stays 12)
        int flags = bb.getShort() & 0xFFFF; // 6..7 flags u16 LE
        bb.getInt();                    // 8..11 codec_pref
        bb.get();                       // 12 mode_flags
        bb.get();                       // 13 channel_mode
        int transport = bb.get() & 0xFF; // 14 transport
        bb.getInt();                    // 15..18 session_hint
        byte[] clientId = new byte[CLIENT_ID_SIZE];
        bb.get(clientId);               // 19..34 client_id
        byte[] authToken = new byte[AUTH_TOKEN_SIZE];
        bb.get(authToken);              // 35..58 auth_token
        // 59..63 reserved — ignored
        return new Greeting(clientId, authToken, flags, transport);
    }

    /**
     * Validates the presented 24-byte auth_token in constant time. Auth is enforced only when
     * {@code vatn.ipc.require_auth_token} is set AND the transport is NOT a Unix Domain Socket
     * (UDS trust is derived from filesystem permissions).
     */
    private boolean validateAuthToken(byte[] presented) {
        if (!Boolean.getBoolean("vatn.ipc.require_auth_token") || isUds) return true;
        byte[] expected = expectedAuthTokenBytes();
        return java.security.MessageDigest.isEqual(expected, presented);
    }

    private static byte[] expectedAuthTokenBytes() {
        byte[] out = new byte[AUTH_TOKEN_SIZE];
        String configured = System.getProperty("vatn.ipc.auth_token", "");
        byte[] src = configured.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, out, 0, Math.min(src.length, AUTH_TOKEN_SIZE));
        return out;
    }

    /** Immutable holder for the identity captured from a v2.13 Greeting. */
    private record Greeting(byte[] clientId, byte[] authToken, int flags, int transport) {}

    private static int readFully(InputStream in, byte[] buf, int offset, int n) throws IOException {
        int total = 0;
        while (total < n) {
            int r = in.read(buf, offset + total, n - total);
            if (r == -1) return total;
            total += r;
        }
        return total;
    }

    private static int readFully(InputStream in, byte[] buf, int n) throws IOException {
        return readFully(in, buf, 0, n);
    }

    /**
     * Reads {@code buf.remaining()} bytes from the channel, enforcing a wall-clock {@code deadline}.
     * Switches the channel to non-blocking and polls so a client that opens a socket but never
     * completes the handshake cannot pin the (virtual) thread indefinitely. Returns the number of
     * bytes actually read, or -1 on EOF before the buffer is filled.
     */
    private static int readBounded(SocketChannel ch, ByteBuffer buf, long deadlineNanos) throws IOException {
        boolean wasBlocking = ch.isBlocking();
        ch.configureBlocking(false);
        try {
            int total = 0;
            while (buf.hasRemaining()) {
                if (System.nanoTime() > deadlineNanos) return total;
                int r = ch.read(buf);
                if (r == -1) return total == 0 ? -1 : total;
                if (r == 0) {
                    try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return total; }
                } else {
                    total += r;
                }
            }
            return total;
        } finally {
            ch.configureBlocking(true);
        }
    }

    private void dispatchToSubscribers(ClientConnection client, String channel, byte[] payload) {
        List<Consumer<byte[]>> consumers = subscribers.get(channel);
        if (consumers != null) {
            consumers.forEach(c -> Thread.ofVirtual().start(() ->
                ScopedValue.where(VatnSecurity.CURRENT_AUTH_TOKEN, client.authToken)
                    .where(VatnSecurity.CURRENT_CLIENT_ID, client.clientId)
                    .run(() -> {
                        try { c.accept(payload); }
                        catch (Exception e) { log.error("Subscriber exception on channel {}", channel, e); }
                    })));
        }
    }

    /**
     * Local (in-process) dispatch with no per-connection identity context
     * (used by {@link #publish(String, byte[])} which is not client-driven).
     */
    private void dispatchToSubscribers(String channel, byte[] payload) {
        List<Consumer<byte[]>> consumers = subscribers.get(channel);
        if (consumers != null) {
            consumers.forEach(c -> Thread.ofVirtual().start(() -> {
                try { c.accept(payload); }
                catch (Exception e) { log.error("Subscriber exception on channel {}", channel, e); }
            }));
        }
    }

    @Override
    public void publish(String channel, byte[] payload) {
        dispatchToSubscribers(channel, payload);

        for (ClientConnection conn : activeClients) {
            synchronized (conn.channel) {
                try {
                    if (payload.length <= CHUNK_SIZE) {
                        // Single V3 frame
                        ByteBuffer buf = ByteBuffer.allocate(V3_HEADER_SIZE + payload.length)
                            .order(ByteOrder.BIG_ENDIAN);
                        buf.put("OIPC".getBytes())
                           .put((byte) 3).put((byte) MASK_BINARY)
                           .putInt(payload.length).putInt(0).putInt(0)
                           .put(payload);
                        conn.channel.write(buf.rewind());
                    } else {
                        // Chunked V3 frames
                        long msgId = ThreadLocalRandom.current().nextInt() & 0xFFFFFFFFL;
                        int chunks = (int) Math.ceil((double) payload.length / CHUNK_SIZE);
                        for (int i = 0; i < chunks; i++) {
                            int offset = i * CHUNK_SIZE;
                            int len = Math.min(CHUNK_SIZE, payload.length - offset);
                            byte flags = (byte) (MASK_BINARY | MASK_CHUNKED
                                | (i == chunks - 1 ? MASK_LAST : 0));
                            ByteBuffer buf = ByteBuffer.allocate(V3_HEADER_SIZE + len)
                                .order(ByteOrder.BIG_ENDIAN);
                            buf.put("OIPC".getBytes())
                               .put((byte) 3).put(flags)
                               .putInt(len).putInt((int) msgId).putInt(i)
                               .put(payload, offset, len);
                            conn.channel.write(buf.rewind());
                        }
                    }
                } catch (IOException e) {
                    conn.close();
                    activeClients.remove(conn);
                }
            }
        }
    }

    @Override
    public void subscribe(String channel, Consumer<byte[]> callback) {
        subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    @Override
    public void unsubscribe(String channel, Consumer<byte[]> callback) {
        List<Consumer<byte[]>> list = subscribers.get(channel);
        if (list != null) list.remove(callback);
    }

    @Override
    public VTransport getTransport() { return isUds ? VTransport.UDS : VTransport.TCP; }

    @Override
    public void sendDirect(String targetNodeId, byte[] payload) {
        publish("direct." + targetNodeId, payload);
    }

    void sendFeedback(ClientConnection target, byte type, long msgId, int seqIdx) {
        synchronized (target.channel) {
            try {
                // Control payload: [Type:1][ControlID:4][Reserved:7] = 12 bytes
                ByteBuffer buf = ByteBuffer.allocate(V3_HEADER_SIZE + 12).order(ByteOrder.BIG_ENDIAN);
                buf.put("OIPC".getBytes())
                   .put((byte) 3).put((byte) (MASK_BINARY | MASK_CONTROL))
                   .putInt(12).putInt((int) msgId).putInt(0);
                buf.put(type).putInt(seqIdx).put(new byte[7]);
                target.channel.write(buf.rewind());
            } catch (IOException e) {
                log.warn("Failed to send feedback to client: {}", e.getMessage());
            }
        }
    }

    private void handleControlFrame(byte[] payload) {
        // type byte reserved for future ACK/NACK handling
    }

    private void handleHello(ClientConnection client, long msgId, byte[] payload) {
        int major  = payload[1] & 0xFF;
        int minor  = payload[2] & 0xFF;
        String nodeId = new String(payload, 3, payload.length - 3, StandardCharsets.UTF_8);

        if (major != 2) {
            log.warn("OIPC Handshake failed: version mismatch (expected V2.x, got V{}.{}) from {}",
                major, minor, nodeId);
            client.close();
            return;
        }

        client.peerNodeId = nodeId;
        client.handshakeComplete = true;
        log.info("OIPC Handshake OK: {} (V2.{})", nodeId, minor);
        sendFeedback(client, (byte) 0x01, msgId, 0); // ACK
    }

    public boolean isUds() { return isUds; }
    public String getConnectionPath() { return connectionPath; }
    public int getConnectionPort() { return connectionPort; }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (serverSocketChannel != null) serverSocketChannel.close();
            for (ClientConnection client : activeClients) client.close();
        } catch (IOException | RuntimeException e) {
            log.warn("Error closing OIPC Transport", e);
        }
    }

    private static class MessageReassembler {
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private int lastSeqIdx = -1;
        long lastUpdate = System.currentTimeMillis();

        synchronized boolean addChunk(int seqIdx, byte[] data) {
            lastUpdate = System.currentTimeMillis();
            if (seqIdx != lastSeqIdx + 1) return false;
            if (buffer.size() + data.length > MAX_MESSAGE_SIZE) return false;
            try { buffer.write(data); lastSeqIdx = seqIdx; return true; }
            catch (IOException e) { return false; }
        }

        synchronized byte[] assemble() { return buffer.toByteArray(); }
    }

    static class ClientConnection {
        final String id = UUID.randomUUID().toString();
        final SocketChannel channel;
        volatile boolean handshakeComplete = false;
        @SuppressWarnings("unused")
        String peerNodeId;
        // v2.13 identity captured from the Greeting bootstrap (Task 4 surfaces these to handlers).
        @SuppressWarnings("unused")
        volatile byte[] clientId;
        @SuppressWarnings("unused")
        volatile byte[] authToken;
        // v2.13 Tunneled_HTTP greeting flag (bit 3) — diagnostic only; does not change handshake.
        @SuppressWarnings("unused")
        volatile boolean tunneledHttp;

        ClientConnection(SocketChannel channel) { this.channel = channel; }
        void close() { try { channel.close(); } catch (IOException e) { log.debug("Error closing client channel {}", id, e); } }
    }
}
