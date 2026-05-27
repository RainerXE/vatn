package dev.vatn.examples.dashboard;

import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VMessaging;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams live JVM metrics to the browser via SSE.
 * A background virtual thread publishes snapshots every second to the
 * "metrics.jvm" topic; the SSE endpoint subscribes and forwards them.
 */
public class DashboardPlugin implements VNodePlugin {

    private static final String TOPIC = "metrics.jvm";

    private VMessaging messaging;
    private final AtomicLong requestCount = new AtomicLong(0);
    private volatile boolean running = true;

    @Override public String getId()      { return "dev.vatn.examples.realtime-dashboard"; }
    @Override public String getName()    { return "Real-time Dashboard"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        this.messaging = ctx.getMessaging();
        startMetricsPublisher();
        ctx.register("/dashboard", new DashboardService(messaging, requestCount));
    }

    @Override
    public void onShutdown() {
        running = false;
    }

    private void startMetricsPublisher() {
        Thread.ofVirtual().name("metrics-publisher").start(() -> {
            Runtime rt = Runtime.getRuntime();
            long startTime = System.currentTimeMillis();
            while (running) {
                try {
                    long used   = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
                    long total  = rt.totalMemory() / 1024 / 1024;
                    long uptime = (System.currentTimeMillis() - startTime) / 1000;
                    String snapshot = String.format(
                            "{\"uptimeS\":%d,\"heapUsedMb\":%d,\"heapTotalMb\":%d,\"requests\":%d,\"ts\":%d}",
                            uptime, used, total, requestCount.get(), System.currentTimeMillis());
                    messaging.publish(TOPIC, snapshot.getBytes(StandardCharsets.UTF_8));
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private static class DashboardService implements VHttpService {

        private final VMessaging messaging;
        private final AtomicLong requestCount;

        // Minimal HTML page with a live-updating display
        private static final String HTML = """
                <!DOCTYPE html>
                <html>
                <head><title>VATN Dashboard</title>
                <style>body{font-family:monospace;background:#0d1117;color:#c9d1d9;padding:2rem}
                .metric{display:inline-block;margin:1rem;padding:1rem 2rem;background:#161b22;border-radius:8px}
                .val{font-size:2rem;font-weight:bold;color:#58a6ff}</style></head>
                <body>
                <h2>⚡ VATN Live Dashboard</h2>
                <div id="uptime" class="metric">Uptime<br><span class="val" id="u">—</span>s</div>
                <div id="heap" class="metric">Heap Used<br><span class="val" id="h">—</span> MB</div>
                <div id="reqs" class="metric">Requests<br><span class="val" id="r">—</span></div>
                <p id="ts" style="opacity:.5"></p>
                <script>
                const es = new EventSource('/dashboard/stream');
                es.addEventListener('metrics', e => {
                  const d = JSON.parse(e.data);
                  document.getElementById('u').textContent = d.uptimeS;
                  document.getElementById('h').textContent = d.heapUsedMb + '/' + d.heapTotalMb;
                  document.getElementById('r').textContent = d.requests;
                  document.getElementById('ts').textContent = new Date(d.ts).toISOString();
                });
                </script>
                </body></html>
                """;

        DashboardService(VMessaging messaging, AtomicLong requestCount) {
            this.messaging = messaging;
            this.requestCount = requestCount;
        }

        @Override
        public void routing(VHttpRoutes routes) {
            routes.get("/", (req, res) -> {
                requestCount.incrementAndGet();
                res.setHeader("Content-Type", "text/html; charset=utf-8");
                res.send(HTML.getBytes(StandardCharsets.UTF_8));
            });

            routes.sse("/stream", (req, sink) -> {
                requestCount.incrementAndGet();
                java.util.function.Consumer<byte[]> callback = payload -> {
                    try {
                        sink.emit("metrics", new String(payload, StandardCharsets.UTF_8), null);
                    } catch (Exception ignored) {}
                };
                messaging.subscribe(TOPIC, callback);
                try {
                    // Hold the SSE connection open until the client disconnects.
                    // VSseSink.close() is called by the framework on client disconnect,
                    // which will cause sink.emit() to throw, breaking the loop above.
                    // We park the virtual thread cheaply.
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    messaging.unsubscribe(TOPIC, callback);
                }
            });

            routes.post("/reset", (req, res) -> {
                requestCount.set(0);
                res.sendJson("{\"reset\":true}");
            });
        }
    }
}
