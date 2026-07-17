package dev.vatn.plugins.terminalphone;

import dev.vatn.api.VService;

import java.util.function.Consumer;

/**
 * Anonymous, E2E-encrypted voice and text over Tor hidden services.
 *
 * <pre>{@code
 * TerminalPhoneService phone = ctx.getService(TerminalPhoneService.class).orElseThrow();
 *
 * phone.onCallConnected(peer -> System.out.println("Connected to " + peer));
 * phone.onVoiceMessage(pcm  -> phone.playVoice(pcm));
 * phone.onTextMessage(msg   -> System.out.println(">> " + msg));
 *
 * phone.call("abc123.onion");
 * phone.sendText("Hello!");
 *
 * byte[] recording = phone.recordVoice(3000);  // 3s push-to-talk
 * phone.sendVoice(recording);
 * }</pre>
 */
public interface TerminalPhoneService extends VService {

    /** The local .onion address (or node-scoped address in non-Tor dev mode). */
    String getLocalAddress();

    /** Dial a peer by their .onion address. Completes after handshake. */
    void call(String onionAddress) throws Exception;

    /** Hang up all active sessions. */
    void hangup();

    /** Send an encrypted text message to all connected peers. */
    void sendText(String message);

    /** Record PCM audio from the microphone for {@code durationMs} milliseconds. */
    byte[] recordVoice(int durationMs) throws Exception;

    /** Encrypt and send raw PCM audio to all connected peers. */
    void sendVoice(byte[] pcmData);

    /** Decrypt and play back received PCM audio through the local speaker. */
    void playVoice(byte[] pcmData) throws Exception;

    /** Called when a voice message arrives from any peer. */
    void onVoiceMessage(Consumer<byte[]> handler);

    /** Called when a text message arrives from any peer. */
    void onTextMessage(Consumer<String> handler);

    /** Called when a peer completes the handshake. Argument is the peer address. */
    void onCallConnected(Consumer<String> handler);

    /** Called when all sessions end. */
    void onCallEnded(Runnable handler);

    /** Returns the local address as a QR code in ASCII-art block characters. */
    String getQrCode();

    /** Number of currently active encrypted sessions. */
    int activeSessions();
}
