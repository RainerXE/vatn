package dev.vatn.core.transport;

import dev.vatn.api.VWsSession;
import io.helidon.websocket.WsSession;

/** Wraps a Helidon WsSession as the VATN VWsSession SPI. */
public class HelidonVWsSession implements VWsSession {

    private final WsSession delegate;

    public HelidonVWsSession(WsSession delegate) {
        this.delegate = delegate;
    }

    @Override
    public void send(String text, boolean last) {
        delegate.send(text, last);
    }

    @Override
    public void close(int statusCode, String reason) {
        delegate.close(statusCode, reason);
    }

    @Override
    public String getPath() {
        return delegate.prologue().uriPath().path();
    }

    @Override
    public String getQueryParam(String name) {
        return delegate.prologue().query().getRaw(name);
    }
}
