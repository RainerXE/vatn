package dev.vatn.plugins.scraper;

import dev.vatn.api.VJson;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
 * VATN plugin that scrapes a batch of HTML pages and pipes the extracted
 * entries as an NDJSON stream to a downstream node.
 */
public class ScraperPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(ScraperPlugin.class);

    @Override public String getId()      { return "dev.vatn.plugins.scraper"; }
    @Override public String getName()    { return "VATN Scraper Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Scraper plugin initialized on node: {}", ctx.getNodeId());
    }

    @Override
    public void onShutdown() {
        log.info("Scraper plugin stopped.");
    }

    /**
     * Fetches each URL in {@code sourceUrls}, extracts title and a content
     * snippet, and streams the results to {@code targetNodeUrl/stream/<streamId>}.
     */
    public void scrapeBatchAndPipe(VNodeContext ctx, List<String> sourceUrls, String targetNodeUrl, String streamId) {
        Thread.ofVirtual().start(() -> {
            try {
                VJson json = ctx.getJson();
                VStream stream = ctx.getStream();
                HttpClient client = HttpClient.newHttpClient();

                String targetPath = targetNodeUrl + "/stream/" + streamId;
                try (OutputStream out = stream.createRemoteOutput(targetPath)) {
                    List<Map<String, String>> results = new ArrayList<>();

                    for (String url : sourceUrls) {
                        String html = client.send(
                                HttpRequest.newBuilder().uri(URI.create(url)).build(),
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

                log.info("Scraped and piped {} URLs.", sourceUrls.size());
            } catch (Exception e) {
                log.error("Error during batch scraping", e);
            }
        });
    }
}
