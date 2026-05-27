package dev.vatn.api;

/**
 * Transport-neutral HTTP response. Extends VResponse with HTTP-specific helpers
 * and a fluent status setter for the common {@code res.status(500).send(...)} pattern.
 */
@VatnApi(since = "1.0")
public interface VHttpResponse extends VResponse {

    /**
     * Sets the HTTP status code and returns {@code this} for fluent chaining:
     * {@code res.status(404).send("Not found")}.
     */
    VHttpResponse status(int code);

    /** Sends raw bytes as the response body. */
    void send(byte[] content);

    /** Sets Content-Type to application/json and sends the JSON string. */
    default void sendJson(String json) {
        setHeader("Content-Type", "application/json");
        send(json);
    }

    /** Sends an empty 204 No Content response. */
    default void sendEmpty() {
        status(204);
        send("");
    }
}
