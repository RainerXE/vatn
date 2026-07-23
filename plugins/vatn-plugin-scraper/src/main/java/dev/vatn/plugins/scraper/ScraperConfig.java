package dev.vatn.plugins.scraper;

public final class ScraperConfig {
    private final int maxConcurrency;
    private final int requestDelayMs;
    private final String userAgent;
    private final int maxContentLength;

    private ScraperConfig(int maxConcurrency, int requestDelayMs, String userAgent, int maxContentLength) {
        this.maxConcurrency = maxConcurrency;
        this.requestDelayMs = requestDelayMs;
        this.userAgent = userAgent;
        this.maxContentLength = maxContentLength;
    }

    public static ScraperConfig defaults() {
        return new ScraperConfig(4, 200, "VATN-Scraper/1.0", 10_000);
    }

    public ScraperConfig withMaxConcurrency(int v) { return new ScraperConfig(v, requestDelayMs, userAgent, maxContentLength); }
    public ScraperConfig withRequestDelayMs(int v) { return new ScraperConfig(maxConcurrency, v, userAgent, maxContentLength); }
    public ScraperConfig withUserAgent(String v) { return new ScraperConfig(maxConcurrency, requestDelayMs, v, maxContentLength); }
    public ScraperConfig withMaxContentLength(int v) { return new ScraperConfig(maxConcurrency, requestDelayMs, userAgent, v); }
    public int getMaxConcurrency() { return maxConcurrency; }
    public int getRequestDelayMs() { return requestDelayMs; }
    public String getUserAgent() { return userAgent; }
    public int getMaxContentLength() { return maxContentLength; }
}
