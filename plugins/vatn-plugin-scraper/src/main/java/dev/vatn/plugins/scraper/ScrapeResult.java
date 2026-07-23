package dev.vatn.plugins.scraper;

public record ScrapeResult(String url, String title, String content, int statusCode, String error) {
}
