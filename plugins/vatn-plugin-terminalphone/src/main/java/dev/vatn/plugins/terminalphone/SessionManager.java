package dev.vatn.plugins.terminalphone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

class SessionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final TerminalPhoneConfig       config;
    private final TorTransport              transport;
    private final CryptoEngine              crypto;
    private final Map<String, PhoneSession> sessions = new ConcurrentHashMap<>();

    private ServerSocket  serverSocket;
    private ExecutorService acceptor;
    private String          localAddress;

    private Consumer<byte[]> globalVoiceHandler;
    private Consumer<String> globalTextHandler;
    private Consumer<String> connectHandler;
    private Runnable         disconnectHandler;

    SessionManager(TerminalPhoneConfig config, TorTransport transport, CryptoEngine crypto) {
        this.config    = config;
        this.transport = transport;
        this.crypto    = crypto;
    }

    void start(String address) throws Exception {
        this.localAddress = address;
        serverSocket = transport.listen(config.listenPort());
        acceptor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terminalphone-acceptor");
            t.setDaemon(true);
            return t;
        });
        acceptor.submit(this::acceptLoop);
        log.info("TerminalPhone listening on port {}", config.listenPort());
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "terminalphone-handshake");
                    t.setDaemon(true);
                    return t;
                }).execute(() -> handshakeIncoming(socket));
            } catch (Exception e) {
                if (!serverSocket.isClosed()) log.warn("Accept error: {}", e.getMessage());
            }
        }
    }

    private void handshakeIncoming(Socket socket) {
        try {
            MessageFramer framer = new MessageFramer(
                socket.getOutputStream(), socket.getInputStream(), crypto, config.hmacSigning());

            PhoneMessage hello = framer.receive();
            if (hello.type() != PhoneMessage.Type.HANDSHAKE) {
                log.warn("Expected HANDSHAKE, got {}", hello.type());
                socket.close();
                return;
            }
            String peerId = new String(hello.payload());
            framer.send(new PhoneMessage(PhoneMessage.Type.HANDSHAKE, localAddress.getBytes()));

            log.info("Incoming call from {}", peerId);
            PhoneSession session = buildSession(peerId, socket, framer);
            if (connectHandler != null) connectHandler.accept(peerId);
            session.activate();

        } catch (Exception e) {
            log.warn("Handshake failed from {}: {}", socket.getRemoteSocketAddress(), e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    PhoneSession dial(String onionAddress) throws Exception {
        Socket socket = transport.connect(onionAddress, config.listenPort());
        MessageFramer framer = new MessageFramer(
            socket.getOutputStream(), socket.getInputStream(), crypto, config.hmacSigning());

        framer.send(new PhoneMessage(PhoneMessage.Type.HANDSHAKE, localAddress.getBytes()));
        PhoneMessage reply = framer.receive();
        String peerId = new String(reply.payload());

        log.info("Connected to {}", peerId);
        PhoneSession session = buildSession(peerId, socket, framer);
        if (connectHandler != null) connectHandler.accept(peerId);
        session.activate();
        return session;
    }

    private PhoneSession buildSession(String peerId, Socket socket, MessageFramer framer) {
        PhoneSession session = new PhoneSession(peerId, socket, framer);
        sessions.put(peerId, session);
        session.onVoice(d -> { if (globalVoiceHandler != null) globalVoiceHandler.accept(d); });
        session.onText(t  -> { if (globalTextHandler  != null) globalTextHandler.accept(t);  });
        session.onEnd(() -> {
            sessions.remove(peerId);
            if (disconnectHandler != null) disconnectHandler.run();
        });
        return session;
    }

    void broadcastVoice(byte[] pcmData) {
        sessions.values().forEach(s -> {
            try { s.sendVoice(pcmData); }
            catch (Exception e) { log.warn("Voice send failed to {}: {}", s.getPeerId(), e.getMessage()); }
        });
    }

    void broadcastText(String message) {
        sessions.values().forEach(s -> {
            try { s.sendText(message); }
            catch (Exception e) { log.warn("Text send failed to {}: {}", s.getPeerId(), e.getMessage()); }
        });
    }

    void hangupAll() {
        sessions.values().forEach(s -> {
            try { s.hangup(); }
            catch (Exception e) { log.debug("Hangup error for {}: {}", s.getPeerId(), e.getMessage()); }
        });
    }

    void onVoice(Consumer<byte[]>  h) { globalVoiceHandler = h; }
    void onText(Consumer<String>   h) { globalTextHandler  = h; }
    void onConnect(Consumer<String> h) { connectHandler     = h; }
    void onDisconnect(Runnable      h) { disconnectHandler  = h; }

    int sessionCount() { return sessions.size(); }

    @Override
    public void close() {
        hangupAll();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (acceptor != null) acceptor.shutdownNow();
    }
}
