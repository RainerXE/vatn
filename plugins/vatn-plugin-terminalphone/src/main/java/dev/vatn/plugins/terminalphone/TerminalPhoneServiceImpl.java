package dev.vatn.plugins.terminalphone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

class TerminalPhoneServiceImpl implements TerminalPhoneService {

    private static final Logger log = LoggerFactory.getLogger(TerminalPhoneServiceImpl.class);

    private final SessionManager sessionManager;
    private final AudioPlayer    player = new AudioPlayer();
    private       AudioCapture   capture;
    private       String         localAddress;

    private Consumer<byte[]> voiceHandler;
    private Consumer<String> textHandler;
    private Consumer<String> connectHandler;
    private Runnable         endHandler;

    TerminalPhoneServiceImpl(SessionManager sessionManager) {
        this.sessionManager = sessionManager;

        sessionManager.onVoice(d    -> { if (voiceHandler   != null) voiceHandler.accept(d);   });
        sessionManager.onText(t     -> { if (textHandler    != null) textHandler.accept(t);    });
        sessionManager.onConnect(a  -> { if (connectHandler != null) connectHandler.accept(a); });
        sessionManager.onDisconnect(() -> {
            if (sessionManager.sessionCount() == 0 && endHandler != null) endHandler.run();
        });
    }

    void setLocalAddress(String address) {
        this.localAddress = address;
    }

    @Override public String getLocalAddress()  { return localAddress; }
    @Override public int    activeSessions()   { return sessionManager.sessionCount(); }

    @Override
    public void call(String onionAddress) throws Exception {
        log.info("Dialing {}", onionAddress);
        sessionManager.dial(onionAddress);
    }

    @Override
    public void hangup() {
        sessionManager.hangupAll();
    }

    @Override
    public void sendText(String message) {
        sessionManager.broadcastText(message);
    }

    @Override
    public byte[] recordVoice(int durationMs) throws Exception {
        if (capture == null) capture = new AudioCapture();
        return capture.capture(durationMs);
    }

    @Override
    public void sendVoice(byte[] pcmData) {
        sessionManager.broadcastVoice(pcmData);
    }

    @Override
    public void playVoice(byte[] pcmData) throws Exception {
        player.play(pcmData);
    }

    @Override public void onVoiceMessage(Consumer<byte[]> h)  { voiceHandler   = h; }
    @Override public void onTextMessage(Consumer<String>  h)  { textHandler    = h; }
    @Override public void onCallConnected(Consumer<String> h) { connectHandler = h; }
    @Override public void onCallEnded(Runnable h)             { endHandler     = h; }

    @Override
    public String getQrCode() {
        return localAddress != null ? QrHelper.toAscii(localAddress) : "No address assigned yet";
    }
}
