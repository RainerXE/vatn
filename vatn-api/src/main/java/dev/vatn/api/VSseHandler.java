package dev.vatn.api;

/**
 * Handler for an SSE (Server-Sent Events) endpoint.
 * The sink is opened before the handler is called and closed automatically
 * when the handler returns (or the client disconnects).
 */
@FunctionalInterface
@VatnApi(since = "1.0")
public interface VSseHandler {

    void handle(VHttpRequest request, VSseSink sink) throws Exception;
}
