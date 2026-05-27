package dev.vatn.plugin.scraper;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VJson;
import dev.vatn.api.VStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A VATN plugin that scrapes HTML and produces JSON entries.
 */
public class ScraperPlugin implements VNodePlugin {

    private static final Logger logger = LoggerFactory.getLogger(ScraperPlugin.class);
    private final PluginWrapper wrapper;

    public ScraperPlugin(PluginWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public String getId() {
        return wrapper != null ? wrapper.getPluginId() : "scraper-agent";
    }

    @Override
    public String getName() {
        return "Scraper Agent";
    }

    @Override
    public String getVersion() {
        return wrapper != null ? wrapper.getDescriptor().getVersion() : "1.0.0";
    }

    @Override
    public void onInitialize(VNodeContext context) {
        logger.info("[Scraper] Agent initialized on node: {}", context.getNodeId());
    }

    /**
     * Scrapes multiple URLs and pipes the results as a single NDJSON stream.
     */
    public void scrapeBatchAndPipe(VNodeContext context, List<String> sourceUrls, String targetNodeUrl, String streamId) {
        Thread.ofVirtual().start(() -> {
            try {
                VJson json = context.getJson();
                VStream stream = context.getStream();
                HttpClient client = HttpClient.newHttpClient();
                
                String targetPath = targetNodeUrl + "/stream/" + streamId;
                try (OutputStream out = stream.createRemoteOutput(targetPath)) {
                    List<Map<String, String>> results = new ArrayList<>();
                    
                    for (String url : sourceUrls) {
                        String html = client.send(HttpRequest.newBuilder().uri(URI.create(url)).build(),
                                        HttpResponse.BodyHandlers.ofString()).body();
                        Document doc = Jsoup.parse(html);
                        String body = doc.body().text();
                        results.add(Map.of(
                            "source", url,
                            "title", doc.title(),
                            "content", body.substring(0, Math.min(body.length(), 50)) + "..."
                        ));
                    }
                    
                    json.stringifyStream(results, out);
                }
                
                logger.info("[Scraper] Batch scraped {} URLs.", sourceUrls.size());
            } catch (Exception e) {
                logger.error("[Scraper] Error during batch scraping", e);
            }
        });
    }

    @Override
    public void onShutdown() {
        logger.info("[Scraper] Agent stopped.");
    }
}
