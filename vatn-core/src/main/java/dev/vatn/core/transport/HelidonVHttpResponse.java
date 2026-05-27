package dev.vatn.core.transport;

import dev.vatn.api.VHttpResponse;
import io.helidon.webserver.http.ServerResponse;

/** Helidon-backed implementation of VHttpResponse. */
public class HelidonVHttpResponse implements VHttpResponse {

    private final ServerResponse res;

    public HelidonVHttpResponse(ServerResponse res) {
        this.res = res;
    }

    @Override
    public VHttpResponse status(int code) {
        res.status(io.helidon.http.Status.create(code));
        return this;
    }

    @Override
    public void setStatus(int code) {
        res.status(io.helidon.http.Status.create(code));
    }

    @Override
    public void setHeader(String name, String value) {
        res.header(name, value);
    }

    @Override
    public void send(String content) {
        res.send(content);
    }

    @Override
    public void send(byte[] content) {
        res.send(content);
    }
}
