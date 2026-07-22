package dev.vatn.plugins.indexer;

import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VJson;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class IndexerPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(IndexerPlugin.class);

    @Override public String getId()      { return "dev.vatn.plugins.indexer"; }
    @Override public String getName()    { return "VATN Indexer Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Indexer plugin initialized on node: {}", ctx.getNodeId());

        InMemoryIndexService indexService = new InMemoryIndexService();
        ctx.registerService(IndexerService.class, indexService);
        ctx.registerHealthCheck("indexer", () -> indexService != null);
        ctx.register("/index", new IndexHttpService(ctx));
    }

    @Override
    public void onShutdown() {
        log.info("Indexer plugin stopped.");
    }

    public void processAndRelay(VNodeContext ctx, String streamId, String nextNodeUrl, String nextStreamId) {
        Thread.ofVirtual().start(() -> {
            try {
                VJson json = ctx.getJson();
                VStream stream = ctx.getStream();
                IndexerService indexer = ctx.getService(IndexerService.class).orElse(null);

                InputStream in = openWithRetry(stream, streamId);
                if (in == null) {
                    throw new RuntimeException("Timeout waiting for stream: " + streamId);
                }

                List<Map<String, Object>> entries = new ArrayList<>();
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<Map> parser = entry -> entries.add((Map<String, Object>) entry);
                json.parseStream(in, Map.class, parser);

                entries.sort(Comparator.comparing(m -> String.valueOf(m.getOrDefault("title", ""))));

                if (indexer != null) {
                    indexer.indexBatch(entries);
                }

                String targetPath = nextNodeUrl + "/stream/" + nextStreamId;
                try (OutputStream out = stream.createRemoteOutput(targetPath)) {
                    json.stringifyStream(entries, out);
                }

                log.info("Indexed and relayed {} entries.", entries.size());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Processing interrupted.");
            } catch (Exception e) {
                log.error("Error during processing", e);
            }
        });
    }

    private InputStream openWithRetry(VStream stream, String streamId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try {
                return stream.openInput(streamId);
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        return null;
    }

    private record IndexHttpService(VNodeContext ctx) implements VHttpService {
        @Override
        public void routing(VHttpRoutes routes) {
            routes.post("/ingest", (req, res) -> {
                String body = req.getBody();
                VJson json = ctx.getJson();
                IndexerService service = ctx.getService(IndexerService.class)
                        .orElseThrow(() -> new IllegalStateException("IndexerService not registered"));

                try {
                    List<Map<String, Object>> docs = json.parse(body, List.class);
                    service.indexBatch(docs);
                } catch (Exception e) {
                    Map<String, Object> doc = json.parse(body, Map.class);
                    service.indexBatch(List.of(doc));
                }
                res.status(200).send("OK");
            });

            routes.get("/search", (req, res) -> {
                String query = req.getQueryParam("q", "");
                IndexerService service = ctx.getService(IndexerService.class)
                        .orElseThrow(() -> new IllegalStateException("IndexerService not registered"));
                List<Map<String, Object>> results = service.search(query);
                res.status(200).sendJson(ctx.getJson().stringify(results));
            });

            routes.get("/{id}", (req, res) -> {
                String id = req.getPathParam("id");
                IndexerService service = ctx.getService(IndexerService.class)
                        .orElseThrow(() -> new IllegalStateException("IndexerService not registered"));
                Map<String, Object> doc = service.get(id);
                if (doc == null) {
                    res.status(404).send("Not found");
                } else {
                    res.status(200).sendJson(ctx.getJson().stringify(doc));
                }
            });

            routes.delete("/clear", (req, res) -> {
                IndexerService service = ctx.getService(IndexerService.class)
                        .orElseThrow(() -> new IllegalStateException("IndexerService not registered"));
                service.clear();
                res.status(200).send("OK");
            });
        }
    }
}
