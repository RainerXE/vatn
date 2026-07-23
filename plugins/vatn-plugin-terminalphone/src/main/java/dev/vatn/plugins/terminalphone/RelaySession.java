package dev.vatn.plugins.terminalphone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zero-knowledge relay: forwards raw encrypted frames between callers without decrypting.
 * Relay operators cannot read message content. Supports 3–5 callers on mobile,
 * 5–10 on dedicated hardware (Tor bandwidth is the bottleneck).
 */
class RelaySession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelaySession.class);

    private final List<Socket>    clients  = new CopyOnWriteArrayList<>();
    private final ServerSocket    server;
    private final ExecutorService executor;

    RelaySession(int port) throws Exception {
        this.server   = new ServerSocket(port);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "terminalphone-relay");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::acceptLoop);
        log.info("TerminalPhone relay node started on port {}", port);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                clients.add(client);
                log.info("Relay client connected — total={}", clients.size());
                executor.submit(() -> relayLoop(client));
            } catch (Exception e) {
                if (!server.isClosed()) log.warn("Relay accept error: {}", e.getMessage());
            }
        }
    }

    private void relayLoop(Socket src) {
        try (src) {
            InputStream in  = src.getInputStream();
            byte[]      buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                final byte[] frame = Arrays.copyOf(buf, n);
                for (Socket peer : clients) {
                    if (peer != src && !peer.isClosed()) {
                        try {
                            peer.getOutputStream().write(frame);
                        } catch (Exception e) {
                            clients.remove(peer);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Relay client disconnected: {}", e.getMessage());
        } finally {
            clients.remove(src);
            log.info("Relay client disconnected — remaining={}", clients.size());
        }
    }

    int clientCount() { return clients.size(); }

    @Override
    public void close() {
        clients.forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
        try { server.close(); } catch (Exception ignored) {}
        executor.shutdownNow();
    }
}
