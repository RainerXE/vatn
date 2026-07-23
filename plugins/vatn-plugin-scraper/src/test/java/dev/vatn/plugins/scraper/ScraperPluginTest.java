package dev.vatn.plugins.scraper;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScraperPluginTest {

    @Test
    void scrapeReturnsTitleAndContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/test", exchange -> {
            String html = """
                    <html>
                      <head><title>Hello World</title></head>
                      <body><p>This is the page content.</p></body>
                    </html>
                    """;
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ScraperService service = new JsoupScraperService(ScraperConfig.defaults()
                    .withRequestDelayMs(0));
            ScrapeResult result = service.scrape("http://localhost:" + port + "/test");

            assertEquals("Hello World", result.title());
            assertTrue(result.content().contains("This is the page content."));
            assertEquals(200, result.statusCode());
            assertNull(result.error());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scrapeReturnsErrorOnBadUrl() {
        ScraperService service = new JsoupScraperService(ScraperConfig.defaults());
        ScrapeResult result = service.scrape("http://localhost:0/nonexistent");
        assertNotNull(result.error());
    }

    @Test
    void scrapeBatchErrorIsolation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/good", exchange -> {
            String html = "<html><head><title>Good</title></head><body><p>OK</p></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ScraperService service = new JsoupScraperService(ScraperConfig.defaults()
                    .withRequestDelayMs(0));
            List<ScrapeResult> results = service.scrapeBatch(List.of(
                    "http://localhost:" + port + "/good",
                    "http://localhost:0/bad",
                    "http://localhost:" + port + "/good"
            ));

            assertEquals(3, results.size());
            long goodCount = results.stream().filter(r -> r.title() != null).count();
            long errorCount = results.stream().filter(r -> r.error() != null).count();
            assertEquals(2, goodCount);
            assertEquals(1, errorCount);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scrapeContentTruncatedToMaxLength() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/long", exchange -> {
            String body = "x".repeat(500);
            String html = "<html><head><title>Long</title></head><body><p>" + body + "</p></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ScraperService service = new JsoupScraperService(ScraperConfig.defaults()
                    .withMaxContentLength(50)
                    .withRequestDelayMs(0));
            ScrapeResult result = service.scrape("http://localhost:" + port + "/long");

            assertEquals("Long", result.title());
            assertEquals(50, result.content().length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scrapeBatchRespectsMaxConcurrency() {
        ScraperService service = new JsoupScraperService(ScraperConfig.defaults()
                .withMaxConcurrency(2));
        List<ScrapeResult> results = service.scrapeBatch(List.of(
                "http://localhost:0/a",
                "http://localhost:0/b",
                "http://localhost:0/c"
        ));

        assertEquals(3, results.size());
        assertEquals(3, results.stream().filter(r -> r.error() != null).count());
    }

    @Test
    void pluginInitializes() {
        var runner = dev.vatn.core.VNodeRunner.create(0);
        try {
            runner.addPlugin(new ScraperPlugin());
            runner.start();
            var ctx = runner.getContext();
            var service = ctx.getService(ScraperService.class);
            assertTrue(service.isPresent());
        } finally {
            runner.stop();
        }
    }
}
