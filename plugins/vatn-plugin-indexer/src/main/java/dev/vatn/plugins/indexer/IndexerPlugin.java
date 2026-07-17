package dev.vatn.plugins.indexer;

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

/**
 * VATN plugin that receives a JSON stream, sorts entries by {@code title},
 * and relays them to a downstream node.
 */
public class IndexerPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(IndexerPlugin.class);

    @Override public String getId()      { return "dev.vatn.plugins.indexer"; }
    @Override public String getName()    { return "VATN Indexer Plugin"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Indexer plugin initialized on node: {}", ctx.getNodeId());
    }

    @Override
    public void onShutdown() {
        log.info("Indexer plugin stopped.");
    }

    /**
     * Reads the named stream, sorts entries by {@code title}, and relays the
     * sorted result to {@code nextNodeUrl/stream/<nextStreamId>}.
     */
    public void processAndRelay(VNodeContext ctx, String streamId, String nextNodeUrl, String nextStreamId) {
        Thread.ofVirtual().start(() -> {
            try {
                VJson json = ctx.getJson();
                VStream stream = ctx.getStream();

                InputStream in = openWithRetry(stream, streamId);
                if (in == null) {
                    throw new RuntimeException("Timeout waiting for stream: " + streamId);
                }

                List<Map<String, Object>> entries = new ArrayList<>();
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<Map> parser = entry -> entries.add((Map<String, Object>) entry);
                json.parseStream(in, Map.class, parser);

                entries.sort(Comparator.comparing(m -> String.valueOf(m.getOrDefault("title", ""))));

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
}
