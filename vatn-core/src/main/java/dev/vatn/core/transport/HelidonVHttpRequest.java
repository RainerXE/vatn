package dev.vatn.core.transport;

import dev.vatn.api.VHttpRequest;
import io.helidon.webserver.http.ServerRequest;

/** Helidon-backed implementation of VHttpRequest. */
public class HelidonVHttpRequest implements VHttpRequest {

    private final ServerRequest req;

    public HelidonVHttpRequest(ServerRequest req) {
        this.req = req;
    }

    @Override
    public String getPathParam(String name) {
        return req.path().pathParameters().get(name);
    }

    @Override
    public String getQueryParam(String name, String defaultValue) {
        return req.query().first(name).orElse(defaultValue);
    }

    @Override
    public String getMethod() {
        return req.prologue().method().text();
    }

    @Override
    public String getPath() {
        return req.path().rawPath();
    }

    @Override
    public String getHeader(String name) {
        return req.headers().value(io.helidon.http.HeaderNames.create(name)).orElse("");
    }

    @Override
    public String getBody() {
        return req.content().as(String.class);
    }

    @Override
    public String getSourceId() {
        return req.remotePeer().address().toString();
    }
}
