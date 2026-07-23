package dev.vatn.plugins.scraper;

import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import dev.vatn.api.VJson;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public class ScraperPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(ScraperPlugin.class);

    private ScraperService service;
    private VJson json;

    @Override public String getId()      { return "dev.vatn.plugins.scraper"; }
    @Override public String getName()    { return "VATN Scraper Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.15-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Scraper plugin initialized on node: {}", ctx.getNodeId());

        json = ctx.getJson();
        ScraperConfig config = ScraperConfig.defaults();
        service = new JsoupScraperService(config);

        ctx.registerService(ScraperService.class, service);
        log.debug("ScraperService registered");

        ctx.registerHealthCheck("scraper", () -> service != null);

        ctx.register("/scrape", routes -> {
            routes.post("", this::handleBatchScrape);
            routes.post("/scrape", this::handleSingleScrape);
        });
        log.info("HTTP routes registered at /scrape");
    }

    @Override
    public void onShutdown() {
        log.info("Scraper plugin stopped.");
    }

    public void scrapeBatchAndPipe(VNodeContext ctx, List<String> sourceUrls, String targetNodeUrl, String streamId) {
        Thread.ofVirtual().start(() -> {
            try {
                VStream stream = ctx.getStream();
                String targetPath = targetNodeUrl + "/stream/" + streamId;
                try (OutputStream out = stream.createRemoteOutput(targetPath)) {
                    List<ScrapeResult> results = service.scrapeBatch(sourceUrls);
                    json.stringifyStream(results, out);
                }
                log.info("Scraped and piped {} URLs.", sourceUrls.size());
            } catch (Exception e) {
                log.error("Error during batch scraping", e);
            }
        });
    }

    private void handleSingleScrape(VHttpRequest req, VHttpResponse res) throws Exception {
        try {
            String body = req.getBody();
            Map<String, Object> parsed = json.parse(body, Map.class);
            String url = (String) parsed.get("url");
            if (url == null || url.isBlank()) {
                res.status(400).send("{\"error\":\"Field 'url' is required\"}");
                return;
            }
            ScrapeResult result = service.scrape(url);
            res.send(json.stringify(result));
        } catch (Exception e) {
            log.error("Error handling single scrape", e);
            res.status(500).send("{\"error\":\"Internal error\"}");
        }
    }

    private void handleBatchScrape(VHttpRequest req, VHttpResponse res) throws Exception {
        try {
            String body = req.getBody();
            Map<String, Object> parsed = json.parse(body, Map.class);
            List<String> urls = (List<String>) parsed.get("urls");
            if (urls == null || urls.isEmpty()) {
                res.status(400).send("{\"error\":\"Field 'urls' must be a non-empty array\"}");
                return;
            }
            List<ScrapeResult> results = service.scrapeBatch(urls);
            res.send(json.stringify(results));
        } catch (Exception e) {
            log.error("Error handling batch scrape", e);
            res.status(500).send("{\"error\":\"Internal error\"}");
        }
    }
}
