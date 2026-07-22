package dev.vatn.core.http;

import io.helidon.common.buffers.BufferData;
import io.helidon.http.Headers;
import io.helidon.http.HttpPrologue;
import io.helidon.webserver.ConnectionContext;
import io.helidon.webserver.http1.Http1ConnectionListener;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConnectionWatchdog implements Http1ConnectionListener {
    private static final long TIMEOUT_MS = Long.getLong("vatn.http.header_read_timeout_ms", 30_000);
    private static final long REAPER_INTERVAL_MS = 1_000;

    private final ConcurrentHashMap<ConnectionContext, Long> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "vatn-http-watchdog");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;

    public void start() {
        running = true;
        reaper.scheduleAtFixedRate(this::reap, REAPER_INTERVAL_MS, REAPER_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        reaper.shutdownNow();
        pending.clear();
    }

    @Override
    public void data(ConnectionContext ctx, BufferData buf) {
        pending.putIfAbsent(ctx, System.currentTimeMillis() + TIMEOUT_MS);
    }

    @Override
    public void data(ConnectionContext ctx, byte[] arr, int off, int len) {
        pending.putIfAbsent(ctx, System.currentTimeMillis() + TIMEOUT_MS);
    }

    @Override
    public void prologue(ConnectionContext ctx, HttpPrologue prologue) {
        pending.putIfAbsent(ctx, System.currentTimeMillis() + TIMEOUT_MS);
    }

    @Override
    public void headers(ConnectionContext ctx, Headers headers) {
        pending.remove(ctx);
    }

    @Override
    public void status(ConnectionContext ctx, io.helidon.http.Status status) {
        // Not used — response status is a send-side event, not relevant for header-read timeout.
    }

    private void reap() {
        if (!running) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<ConnectionContext, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ConnectionContext, Long> e = it.next();
            ConnectionContext ctx = e.getKey();
            if (!ctx.serverSocket().isConnected()) {
                it.remove();
                continue;
            }
            if (now >= e.getValue()) {
                try {
                    ctx.serverSocket().close();
                } catch (Exception ex) {
                    // connection already closed or closing
                }
                it.remove();
            }
        }
    }
}
