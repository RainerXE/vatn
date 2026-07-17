package dev.vatn.plugins.terminalphone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

class PhoneSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PhoneSession.class);

    enum State { CONNECTING, ACTIVE, ENDED }

    private final String          peerId;
    private final Socket          socket;
    private final MessageFramer   framer;
    private final ExecutorService executor;
    private volatile State         state = State.CONNECTING;

    private Consumer<byte[]> voiceHandler;
    private Consumer<String> textHandler;
    private Runnable         endHandler;

    PhoneSession(String peerId, Socket socket, MessageFramer framer) {
        this.peerId   = peerId;
        this.socket   = socket;
        this.framer   = framer;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terminalphone-recv-" + peerId);
            t.setDaemon(true);
            return t;
        });
    }

    String   getPeerId() { return peerId; }
    State    getState()  { return state;  }

    void onVoice(Consumer<byte[]> h) { voiceHandler = h; }
    void onText(Consumer<String>  h) { textHandler  = h; }
    void onEnd(Runnable           h) { endHandler   = h; }

    void activate() {
        state = State.ACTIVE;
        executor.submit(this::receiveLoop);
    }

    void sendVoice(byte[] pcmData) throws Exception {
        framer.send(new PhoneMessage(PhoneMessage.Type.VOICE, pcmData));
    }

    void sendText(String message) throws Exception {
        framer.send(new PhoneMessage(PhoneMessage.Type.TEXT, message.getBytes()));
    }

    void hangup() throws Exception {
        framer.send(new PhoneMessage(PhoneMessage.Type.HANGUP, new byte[0]));
        close();
    }

    private void receiveLoop() {
        try {
            while (state == State.ACTIVE && !socket.isClosed()) {
                PhoneMessage msg = framer.receive();
                switch (msg.type()) {
                    case VOICE  -> { if (voiceHandler != null) voiceHandler.accept(msg.payload()); }
                    case TEXT   -> { if (textHandler  != null) textHandler.accept(new String(msg.payload())); }
                    case HANGUP -> { close(); return; }
                    default     -> log.debug("Unexpected message type {} from {}", msg.type(), peerId);
                }
            }
        } catch (Exception e) {
            if (state == State.ACTIVE) {
                log.warn("Session {} terminated unexpectedly: {}", peerId, e.getMessage());
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (state == State.ENDED) return;
        state = State.ENDED;
        try { socket.close(); } catch (Exception ignored) {}
        executor.shutdownNow();
        if (endHandler != null) endHandler.run();
    }
}
