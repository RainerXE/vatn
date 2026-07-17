package dev.vatn.api;

/**
 * Transport-neutral representation of an active WebSocket session.
 * Allows VWsListener implementations to send messages and close
 * the connection without importing any Helidon or container types.
 */
@VatnApi(since = "1.0")
public interface VWsSession {

    /** Sends a text frame. {@code last=true} marks the final fragment. */
    void send(String text, boolean last);

    /** Sends a complete text message (last=true). */
    default void send(String text) {
        send(text, true);
    }

    /** Closes the session with the given WebSocket status code and reason. */
    void close(int statusCode, String reason);

    /** Returns the path of the WebSocket request. */
    default String getPath() {
        return null;
    }

    /** Returns a query parameter from the upgrade request. */
    default String getQueryParam(String name) {
        return null;
    }
}
