package dev.vatn.api;

/**
 * Transport-neutral WebSocket event listener.
 * Implement this interface to handle WebSocket connections without
 * importing any Helidon or container types. Register via
 * {@code VNodeRunner.registerWebSocket(path, listener)}.
 */
@VatnApi(since = "1.0")
public interface VWsListener {

    /** Called when the WebSocket connection is established. */
    default void onOpen(VWsSession session) {}

    /** Called for each incoming text message. {@code last=true} on the final fragment. */
    void onMessage(VWsSession session, String text, boolean last);

    /** Called when the connection is closed normally. */
    default void onClose(VWsSession session, int statusCode, String reason) {}

    /** Called when a protocol or I/O error occurs. */
    default void onError(VWsSession session, Throwable t) {}
}
