package dev.vatn.core.transport;

import dev.vatn.api.VRequest;
import io.helidon.webserver.http.ServerRequest;

/**
 * Helidon-backed implementation of VRequest.
 */
public class HelidonVRequest implements VRequest {
    
    private final ServerRequest request;

    public HelidonVRequest(ServerRequest request) {
        this.request = request;
    }

    @Override
    public String getHeader(String name) {
        return request.headers().value(io.helidon.http.HeaderNames.create(name)).orElse("");
    }

    @Override
    public String getBody() {
        return request.content().as(String.class);
    }

    @Override
    public String getSourceId() {
        return request.remotePeer().address().toString();
    }
}
