package dev.vatn.plugins.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

public class JsoupScraperService implements ScraperService {

    private static final Logger log = LoggerFactory.getLogger(JsoupScraperService.class);

    private final HttpClient httpClient;
    private final ScraperConfig config;

    public JsoupScraperService(ScraperConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ScrapeResult scrape(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", config.getUserAgent())
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Document doc = Jsoup.parse(response.body());
            String bodyText = doc.body().text();
            String content = bodyText.length() > config.getMaxContentLength()
                    ? bodyText.substring(0, config.getMaxContentLength())
                    : bodyText;
            return new ScrapeResult(url, doc.title(), content, response.statusCode(), null);
        } catch (Exception e) {
            log.warn("Failed to scrape {}: {}", url, e.toString());
            return new ScrapeResult(url, null, null, 0, e.getMessage());
        }
    }

    @Override
    public List<ScrapeResult> scrapeBatch(List<String> urls) {
        Semaphore semaphore = new Semaphore(config.getMaxConcurrency());
        List<ScrapeResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (String url : urls) {
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    semaphore.acquire();
                    try {
                        ScrapeResult result = scrape(url);
                        results.add(result);
                        if (config.getRequestDelayMs() > 0 && result.error() == null) {
                            Thread.sleep(config.getRequestDelayMs());
                        }
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return new ArrayList<>(results);
    }
}
