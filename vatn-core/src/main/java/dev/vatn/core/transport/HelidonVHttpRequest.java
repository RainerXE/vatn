package dev.vatn.core.transport;

import dev.vatn.api.VHttpRequest;
import io.helidon.webserver.http.ServerRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Helidon-backed implementation of VHttpRequest. */
public class HelidonVHttpRequest implements VHttpRequest {

    private final ServerRequest req;
    private final Map<String, Object> attributes = new HashMap<>();

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
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @Override
    public <T> Optional<T> getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
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
    public byte[] getBodyBytes() {
        return req.content().as(byte[].class);
    }

    @Override
    public String getSourceId() {
        return req.remotePeer().address().toString();
    }
}
