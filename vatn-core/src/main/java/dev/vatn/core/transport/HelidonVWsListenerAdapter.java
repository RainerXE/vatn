package dev.vatn.core.transport;

import dev.vatn.api.VWsListener;
import dev.vatn.api.VWsSession;
import io.helidon.websocket.WsListener;
import io.helidon.websocket.WsSession;

/** Adapts a VATN VWsListener to the Helidon WsListener interface. */
public class HelidonVWsListenerAdapter implements WsListener {

    private final VWsListener delegate;

    public HelidonVWsListenerAdapter(VWsListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onOpen(WsSession session) {
        delegate.onOpen(new HelidonVWsSession(session));
    }

    @Override
    public void onMessage(WsSession session, String text, boolean last) {
        delegate.onMessage(new HelidonVWsSession(session), text, last);
    }

    @Override
    public void onClose(WsSession session, int status, String reason) {
        delegate.onClose(new HelidonVWsSession(session), status, reason);
    }

    @Override
    public void onError(WsSession session, Throwable t) {
        delegate.onError(new HelidonVWsSession(session), t);
    }
}
