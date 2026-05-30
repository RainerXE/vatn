package dev.vatn.api;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Content/blob-store SPI: streaming, byte-range reads, a pin/evict local cache, and
 * content-addressing.
 *
 * <p>Unlike {@link VFileService} (a path-oriented filesystem abstraction), {@code VBlobStore}
 * manages opaque immutable blobs identified either by a caller-chosen <em>key</em> or by their
 * <em>content hash</em> (content-addressed storage, CAS). The default runtime implementation is a
 * local content-addressed cache with pin/evict; remote backends (S3, etc.) are provided as plugins
 * that implement this same SPI, so application code is backend-agnostic.
 *
 * <h3>Content-addressed write (dedup by hash)</h3>
 * <pre>{@code
 * VBlobStore blobs = ctx.getService(VBlobStore.class).orElseThrow();
 * String hash = blobs.putContent(coverImageStream, "image/jpeg"); // sha-256, e.g. "sha256:ab12…"
 * }</pre>
 *
 * <h3>Named write + range read</h3>
 * <pre>{@code
 * blobs.put("covers/42.jpg", bytes, "image/jpeg");
 * try (InputStream head = blobs.openRange("covers/42.jpg", 0, 1024)) { ... } // first 1 KiB
 * }</pre>
 *
 * <h3>Cache pinning</h3>
 * <pre>{@code
 * blobs.pin(hash);             // never evicted automatically
 * blobs.evictToFit(512L * 1024 * 1024); // LRU-evict unpinned blobs down to 512 MiB
 * }</pre>
 */
@VatnApi(since = "1.2")
public interface VBlobStore extends VService {

    /** Metadata about a stored blob. */
    record BlobStat(
            String  key,
            long    size,
            String  contentType,
            String  contentHash,   // "sha256:<hex>", or null if unknown
            Instant createdAt,
            Instant lastAccessedAt,
            boolean pinned
    ) {}

    // ── content-addressed writes ────────────────────────────────────────────────

    /**
     * Stores bytes under their content hash (sha-256). Returns the content-addressed key
     * ({@code "sha256:<hex>"}). Writing identical bytes again is idempotent (deduplicated).
     */
    String putContent(byte[] data, String contentType) throws IOException;

    /** Streaming variant of {@link #putContent(byte[], String)}. The stream is fully consumed. */
    String putContent(InputStream in, String contentType) throws IOException;

    // ── named writes ──────────────────────────────────────────────────────────

    /** Stores bytes under an explicit key, overwriting any existing blob at that key. */
    void put(String key, byte[] data, String contentType) throws IOException;

    /** Streaming variant of {@link #put(String, byte[], String)}. */
    void put(String key, InputStream in, long contentLength, String contentType) throws IOException;

    // ── reads ───────────────────────────────────────────────────────────────────

    /** Returns the full blob as a byte array. */
    byte[] get(String key) throws IOException;

    /** Opens a stream over the full blob. Caller must close it. */
    InputStream openStream(String key) throws IOException;

    /**
     * Opens a stream over a byte range of the blob (HTTP range semantics).
     *
     * @param key    the blob key
     * @param offset zero-based start offset
     * @param length number of bytes to read; {@code -1} reads to the end of the blob
     */
    InputStream openRange(String key, long offset, long length) throws IOException;

    // ── metadata / listing ──────────────────────────────────────────────────────

    boolean exists(String key);

    Optional<BlobStat> stat(String key);

    /** Lists keys beginning with {@code prefix} (use {@code ""} for all). */
    List<String> list(String prefix);

    /** Deletes a blob. No-op if absent. */
    void delete(String key) throws IOException;

    // ── cache pin / evict ───────────────────────────────────────────────────────

    /** Marks a blob as pinned — exempt from automatic eviction. */
    void pin(String key);

    /** Removes the pin from a blob, making it eligible for eviction. */
    void unpin(String key);

    /** Immediately evicts a single blob from the local cache (a no-op for non-cache backends). */
    void evict(String key) throws IOException;

    /**
     * Evicts unpinned blobs in least-recently-accessed order until the total stored size is at or
     * below {@code targetBytes}. Pinned blobs are never evicted.
     *
     * @return the number of blobs evicted
     */
    int evictToFit(long targetBytes) throws IOException;

    /** Total bytes currently stored (pinned + unpinned). */
    long totalSize();
}
