package dev.vatn.plugin.indexer;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VJson;
import dev.vatn.api.VStream;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A VATN plugin that receives a JSON stream, sorts the entries, and re-pipes them.
 */
public class IndexerPlugin implements VNodePlugin {

    private static final Logger logger = LoggerFactory.getLogger(IndexerPlugin.class);
    private final PluginWrapper wrapper;

    public IndexerPlugin(PluginWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public String getId() {
        return wrapper != null ? wrapper.getPluginId() : "indexer-agent";
    }

    @Override
    public String getName() {
        return "Indexer Agent";
    }

    @Override
    public String getVersion() {
        return wrapper != null ? wrapper.getDescriptor().getVersion() : "1.0.0";
    }

    @Override
    public void onInitialize(VNodeContext context) {
        logger.info("[Indexer] Agent initialized on node: {}", context.getNodeId());
    }

    /**
     * Processes a stream by sorting entries by 'title'.
     */
    public void processAndRelay(VNodeContext context, String streamId, String nextNodeUrl, String nextStreamId) {
        Thread.ofVirtual().start(() -> {
            try {
                VJson json = context.getJson();
                VStream stream = context.getStream();

                // 1. Open incoming stream — poll until available (bounded retry, max 5 s)
                InputStream in = null;
                for (int i = 0; i < 50; i++) {
                    try {
                        in = stream.openInput(streamId);
                        break;
                    } catch (Exception e) {
                        //noinspection BusyWait — intentional bounded retry for stream availability
                        Thread.sleep(100);
                    }
                }
                
                if (in == null) {
                    throw new RuntimeException("Timeout waiting for stream: " + streamId);
                }

                List<Map<String, Object>> entries = new ArrayList<>();
                
                // 2. Parse NDJSON into a collection
                @SuppressWarnings("unchecked")
                java.util.function.Consumer<Map> parser = entry -> entries.add((Map<String, Object>) entry);
                json.parseStream(in, Map.class, parser);
                
                // 3. Perform "Indexing/Sorting" (The secret sauce processor)
                entries.sort(Comparator.comparing(m -> String.valueOf(m.getOrDefault("title", ""))));
                
                // 4. Relay to next node
                String targetPath = nextNodeUrl + "/stream/" + nextStreamId;
                try (OutputStream out = stream.createRemoteOutput(targetPath)) {
                    json.stringifyStream(entries, out);
                }
                
                logger.info("[Indexer] Processed and relayed {} entries.", entries.size());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.warn("[Indexer] Processing interrupted.");
            } catch (Exception e) {
                logger.error("[Indexer] Error during processing", e);
            }
        });
    }

    @Override
    public void onShutdown() {
        logger.info("[Indexer] Agent stopped.");
    }
}
