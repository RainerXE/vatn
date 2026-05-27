package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.plugins.scraper.ScraperPlugin;
import dev.vatn.plugins.indexer.IndexerPlugin;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 3-Node Federated Pipeline Stress Test.
 * Node A (Scraper) -> Node B (Indexer/Sorter) -> Node C (Archive).
 */
public class FederatedPipelineTest {

    private static WebServer mockSite;

    private static int mockSitePort;

    @BeforeAll
    public static void startMockSite() {
        mockSite = WebServer.builder()
                .port(0)
                .routing(HttpRouting.builder()
                        .get("/article1", (req, res) -> res.send("<html><head><title>Z-News</title></head><body>Last Article</body></html>"))
                        .get("/article2", (req, res) -> res.send("<html><head><title>A-News</title></head><body>First Article</body></html>")))
                .build()
                .start();
        mockSitePort = mockSite.port();
    }

    @AfterAll
    public static void stopMockSite() {
        if (mockSite != null) mockSite.stop();
    }

    @Test
    public void test3NodePipeline() throws Exception {
        // We use Mocked Wrapper since we aren't loading from JAR in this unit test for simplicity,
        // but we use the REAL plugin classes.
        
        VNodeRunner nodeA = VNodeRunner.create(0);
        VNodeRunner nodeB = VNodeRunner.create(0);
        VNodeRunner nodeC = VNodeRunner.create(0);

        ScraperPlugin scraper = new ScraperPlugin();
        IndexerPlugin indexer = new IndexerPlugin();

        nodeA.addPlugin(scraper);
        nodeB.addPlugin(indexer);

        nodeA.start();
        nodeB.start();
        nodeC.start();

        int portB = nodeB.getBoundPort();
        int portC = nodeC.getBoundPort();

        try {
            String streamIdAB = "scraper-to-indexer";
            String streamIdBC = "indexer-to-archive";
            CompletableFuture<List<Map<String, Object>>> resultFuture = new CompletableFuture<>();
            List<Map<String, Object>> receivedEntries = new ArrayList<>();

            // 1. Setup Node C (Archive) to listen for the final result
            Thread.ofVirtual().start(() -> {
                try {
                    InputStream in = null;
                    for (int i = 0; i < 50; i++) {
                        try {
                            in = nodeC.getContext().getService(dev.vatn.api.VStream.class).orElseThrow().openInput(streamIdBC);
                            break;
                        } catch (Exception e) {
                            try { Thread.sleep(100); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
                        }
                    }
                    if (in != null) {
                        nodeC.getContext().getJson().parseStream(in, Map.class, entry -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mapEntry = (Map<String, Object>) entry;
                            receivedEntries.add(mapEntry);
                            if (receivedEntries.size() == 2) {
                                resultFuture.complete(receivedEntries);
                            }
                        });
                    }
                } catch (RuntimeException e) {
                    resultFuture.completeExceptionally(e);
                }
            });

            // 2. Setup Node B (Indexer) to start processing when streamIdAB arrives
            Thread.ofVirtual().start(() -> {
                indexer.processAndRelay(nodeB.getContext(), streamIdAB, "http://localhost:" + portC, streamIdBC);
            });

            // 3. Start Node A (Scraper) - Scrape two URLs in one batch
            scraper.scrapeBatchAndPipe(nodeA.getContext(),
                List.of("http://localhost:" + mockSitePort + "/article1",
                        "http://localhost:" + mockSitePort + "/article2"),
                "http://localhost:" + portB, streamIdAB);

            // 4. Verification
            List<Map<String, Object>> results = resultFuture.get(15, TimeUnit.SECONDS);
            assertEquals(2, results.size(), "Should have received 2 entries");
            
            // Log for clarity
            System.out.println("[Test] Received entry 1: " + results.get(0).get("title"));
            System.out.println("[Test] Received entry 2: " + results.get(1).get("title"));

            // Because of the Indexer's sort, "A-News" should come before "Z-News"
            assertEquals("A-News", results.get(0).get("title"), "Sorting failed: A-News expected first!");
            assertEquals("Z-News", results.get(1).get("title"), "Sorting failed: Z-News expected second!");

        } finally {
            nodeA.stop();
            nodeB.stop();
            nodeC.stop();
        }
    }
}
