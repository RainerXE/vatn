# vatn-plugin-scraper
![Stability](https://img.shields.io/badge/stability-preview-yellow)

First-stage pipeline plugin that fetches web pages with Jsoup, extracts structured fields via CSS selectors, and streams results as NDJSON to a downstream VATN node.

## How it works

Uses Jsoup to make HTTP GET requests for each configured URL and applies CSS selectors to extract `title`, `url`, and `description` fields from each page. Extracted entries are serialised as NDJSON and written to a downstream VATN node via `VStream`. The plugin registers HTTP endpoints so the scraping pipeline can be triggered remotely. It is designed as the first stage in a scraper → indexer → storage pipeline, paired with `vatn-plugin-indexer`.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-scraper</artifactId>
    <version>1.0-alpha.15</version>
</dependency>
```

```java
VNodeRunner.create()
    .addPlugin(new ScraperPlugin())
    .run();
```

## API

`ScraperPlugin` registers HTTP endpoints rather than a service interface. Trigger a scrape run via the registered endpoint, passing the target URLs and downstream node details in the request body.

```bash
# Trigger a scrape job via the registered HTTP endpoint
curl -X POST http://localhost:8080/scrape \
  -H "Content-Type: application/json" \
  -d '{
    "urls": ["https://news.ycombinator.com"],
    "selectors": {
      "title": ".titleline > a",
      "url": ".titleline > a",
      "description": ".subtext"
    },
    "nextNodeUrl": "http://indexer-node:8081",
    "nextStreamId": "scraper-output"
  }'
```

## Configuration

| Option         | Default | Meaning                                                     |
|----------------|---------|-------------------------------------------------------------|
| `urls`         | —       | List of URLs to fetch                                       |
| `selectors`    | —       | CSS selector map for `title`, `url`, and `description` fields|
| `nextNodeUrl`  | —       | Base URL of the downstream VATN node to stream results to   |
| `nextStreamId` | —       | Stream ID on the downstream node that receives the NDJSON   |

## Notes

- Jsoup follows redirects by default; set a `userAgent` in Jsoup connection settings if the target site blocks default user-agent strings.
- Each extracted entry is written immediately to the output stream as a single NDJSON line, so the downstream node begins receiving data before the full scrape completes.
- There is no built-in rate limiting or politeness delay between requests; add `Thread.sleep` or a scheduler in front of pipeline triggers for crawl-rate control.
- CSS selector syntax follows Jsoup's implementation, which is a subset of CSS3.
