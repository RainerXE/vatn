package dev.vatn.api;

/**
 * Transport-neutral SSE (Server-Sent Events) sink.
 * Obtained from VHttpRoutes.sse() — do not create directly.
 * Must be closed (try-with-resources) to flush and terminate the event stream.
 */
@VatnApi(since = "1.0")
public interface VSseSink extends AutoCloseable {

    /** Emits a named SSE event with data and an optional replay ID. */
    void emit(String name, String data, String id);

    /** Emits a data-only SSE event (no name, no ID). */
    default void emit(String data) {
        emit(null, data, null);
    }

    @Override
    void close();
}
