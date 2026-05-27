package dev.vatn.core.transport;

import dev.vatn.api.VResponse;
import io.helidon.webserver.http.ServerResponse;

/**
 * Helidon-backed implementation of VResponse.
 */
public class HelidonVResponse implements VResponse {
    
    private final ServerResponse response;

    public HelidonVResponse(ServerResponse response) {
        this.response = response;
    }

    @Override
    public void setHeader(String name, String value) {
        response.header(name, value);
    }

    @Override
    public void send(String content) {
        response.send(content);
    }

    @Override
    public void setStatus(int code) {
        response.status(io.helidon.http.Status.create(code));
    }
}
