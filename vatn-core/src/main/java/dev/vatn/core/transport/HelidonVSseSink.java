package dev.vatn.core.transport;

import dev.vatn.api.VSseSink;
import io.helidon.http.sse.SseEvent;
import io.helidon.webserver.sse.SseSink;

/** Wraps a Helidon SseSink as the VATN VSseSink SPI. */
public class HelidonVSseSink implements VSseSink {

    private final SseSink delegate;

    public HelidonVSseSink(SseSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void emit(String name, String data, String id) {
        SseEvent.Builder builder = SseEvent.builder().data(data != null ? data : "");
        if (name != null) builder.name(name);
        if (id != null) builder.id(id);
        delegate.emit(builder.build());
    }

    @Override
    public void close() {
        delegate.close();
    }
}
