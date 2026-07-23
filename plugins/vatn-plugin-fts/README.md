# vatn-plugin-fts
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Full-text search backed by SQLite FTS5 with BM25 ranking and snippet extraction.

## How it works

Creates an FTS5 virtual table inside the node's existing `VPersistenceService` database — no separate infrastructure is needed. Documents are stored in named collections so multiple independent search indexes can coexist. Queries are ranked with BM25 where the `title` column is weighted 10× over `body`. Snippets are produced by SQLite's built-in `snippet()` function. Tokenisation uses the `porter unicode61` configuration, which gives stemming and Unicode-aware case folding.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-fts</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
VNodeRunner.create()
    .addPlugin(new FtsPlugin())
    .run();
```

## API

```java
public interface FtsService {
    void index(String collection, String docId, String title, String body);
    void delete(String collection, String docId);
    void clear(String collection);
    List<FtsResult> search(String query, int limit);
    List<FtsResult> search(String collection, String query, int limit);
    long count(String collection);
}

// FtsResult record
String  docId();
String  collection();
double  score();
String  snippet();
```

```java
FtsService fts = ctx.service(FtsService.class);

// Index a document
fts.index("articles", "post-42", "VATN plugins", "Drop-in plugins for the VATN runtime.");

// Search across all collections
List<FtsResult> results = fts.search("VATN plugin", 10);

// Search within a specific collection
List<FtsResult> articles = fts.search("articles", "runtime", 5);
for (FtsResult r : articles) {
    System.out.println(r.docId() + " (" + r.score() + "): " + r.snippet());
}

// Remove a document
fts.delete("articles", "post-42");

// Count indexed documents
long total = fts.count("articles");
```

## Configuration

This plugin has no configuration options. It attaches automatically to the node's persistence database.

## Notes

- The FTS5 `porter` stemmer is English-focused; other languages still benefit from `unicode61` case folding but not stemming.
- `search(query, limit)` without a collection searches across all collections and merges BM25 scores.
- `clear(collection)` deletes all documents in the collection but keeps the FTS5 table structure intact.
- FTS5 queries support phrase search (`"exact phrase"`), prefix search (`vatn*`), and column filters (`title:VATN`).
