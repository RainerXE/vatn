# vatn-plugin-indexer
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Pipeline stage that reads a JSON stream, sorts entries by title, and relays the sorted output to a remote VATN node.

## How it works

`IndexerPlugin.processAndRelay` reads all entries from a local stream opened via `VStream.openInput(streamId)`, buffers them in memory, sorts them alphabetically by their `title` field, then writes the sorted NDJSON to a remote node using `VStream.createRemoteOutput(url)`. It is designed to sit between a scraper stage (which produces raw NDJSON) and a storage stage (which expects ordered input). No service interface is registered.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-indexer</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
VNodeRunner.create()
    .addPlugin(new IndexerPlugin())
    .run();
```

## API

`IndexerPlugin` exposes a static utility method rather than a registered service:

```java
public class IndexerPlugin {
    public static void processAndRelay(
        VNodeContext ctx,
        String       streamId,
        String       nextNodeUrl,
        String       nextStreamId
    );
}
```

```java
// Inside a route handler or scheduled task:
IndexerPlugin.processAndRelay(
    ctx,
    "scraper-output-stream",         // local input stream id
    "http://storage-node:8080",      // URL of the next node
    "storage-input-stream"           // stream id on that node
);
```

## Configuration

This plugin has no configuration options. The stream IDs and target URL are passed directly to `processAndRelay`.

## Notes

- All entries are buffered in the JVM heap before sorting — avoid using this for streams with millions of documents.
- Sorting is performed on the `title` field only; entries missing the field sort to the top (null-first ordering).
- The remote output stream is opened once and written sequentially; if the remote node is unavailable the call throws.
- This plugin is intended for use in a three-node pipeline: `vatn-plugin-scraper` → `vatn-plugin-indexer` → a storage node.
