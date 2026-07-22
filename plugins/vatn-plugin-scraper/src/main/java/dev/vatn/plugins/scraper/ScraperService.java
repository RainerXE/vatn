package dev.vatn.plugins.scraper;

import dev.vatn.api.VService;
import java.util.List;

public interface ScraperService extends VService {
    ScrapeResult scrape(String url);
    List<ScrapeResult> scrapeBatch(List<String> urls);
}
