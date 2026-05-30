package dev.vatn.core.blob;

import dev.vatn.api.VBlobStore;
import dev.vatn.api.VPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Local, content-addressed {@link VBlobStore} with a pin/evict cache.
 *
 * <p>Blob bytes are stored on disk under {@code <baseDir>/objects/<aa>/<bb>/<sha256hex>} — keyed by
 * content hash, so identical content is stored once regardless of how many keys reference it.
 * The {@code vatn_blobs} table maps keys → content hash and tracks size, pin state, and access
 * time for LRU eviction. Range reads are served directly from the on-disk object.
 */
public class LocalBlobStore implements VBlobStore {

    private static final Logger log = LoggerFactory.getLogger(LocalBlobStore.class);
    private static final String HASH_PREFIX = "sha256:";

    private final VPersistenceService db;
    private final Path objectsDir;
    private final Path tmpDir;

    public LocalBlobStore(VPersistenceService db, Path baseDir) {
        this.db = db;
        this.objectsDir = baseDir.resolve("objects");
        this.tmpDir = baseDir.resolve("tmp");
        try {
            Files.createDirectories(objectsDir);
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise blob store at " + baseDir, e);
        }
    }

    // ── content-addressed writes ────────────────────────────────────────────────

    @Override
    public String putContent(byte[] data, String contentType) throws IOException {
        return putContent(new ByteArrayInputStream(data), contentType);
    }

    @Override
    public String putContent(InputStream in, String contentType) throws IOException {
        Stored stored = spool(in);
        String key = HASH_PREFIX + stored.hashHex;
        upsert(key, stored.hashHex, stored.size, contentType);
        return key;
    }

    // ── named writes ──────────────────────────────────────────────────────────

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        put(key, new ByteArrayInputStream(data), data.length, contentType);
    }

    @Override
    public void put(String key, InputStream in, long contentLength, String contentType) throws IOException {
        Stored stored = spool(in);
        upsert(key, stored.hashHex, stored.size, contentType);
    }

    /** Streams {@code in} to a temp file, hashing as it goes, then moves it into the object store. */
    private Stored spool(InputStream in) throws IOException {
        MessageDigest digest = sha256();
        Path tmp = Files.createTempFile(tmpDir, "blob-", ".part");
        long size;
        try (DigestInputStream dis = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(tmp)) {
            size = dis.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        String hashHex = HexFormat.of().formatHex(digest.digest());
        Path target = objectPath(hashHex);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            Files.deleteIfExists(tmp);  // dedup: content already stored
        } else {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Stored(hashHex, size);
    }

    // ── reads ───────────────────────────────────────────────────────────────────

    @Override
    public byte[] get(String key) throws IOException {
        try (InputStream in = openStream(key)) {
            return in.readAllBytes();
        }
    }

    @Override
    public InputStream openStream(String key) throws IOException {
        String hash = requireHash(key);
        touch(key);
        return Files.newInputStream(objectPath(hash));
    }

    @Override
    public InputStream openRange(String key, long offset, long length) throws IOException {
        String hash = requireHash(key);
        touch(key);
        InputStream in = Files.newInputStream(objectPath(hash));
        if (offset > 0) {
            in.skipNBytes(offset);
        }
        return length < 0 ? in : new BoundedInputStream(in, length);
    }

    // ── metadata / listing ──────────────────────────────────────────────────────

    @Override
    public boolean exists(String key) {
        return lookupHash(key) != null;
    }

    @Override
    public Optional<BlobStat> stat(String key) {
        String sql = "SELECT key, content_hash, size, content_type, pinned, created_at, last_accessed_at "
                + "FROM vatn_blobs WHERE key=?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new BlobStat(
                        rs.getString("key"),
                        rs.getLong("size"),
                        rs.getString("content_type"),
                        HASH_PREFIX + rs.getString("content_hash"),
                        parseInstant(rs.getString("created_at")),
                        parseInstant(rs.getString("last_accessed_at")),
                        rs.getInt("pinned") != 0));
            }
        } catch (SQLException e) {
            throw new RuntimeException("stat failed for " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        List<String> keys = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT key FROM vatn_blobs WHERE key LIKE ? ORDER BY key")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("list failed for prefix " + prefix, e);
        }
        return keys;
    }

    @Override
    public void delete(String key) throws IOException {
        String hash = lookupHash(key);
        if (hash == null) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM vatn_blobs WHERE key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("delete failed for " + key, e);
        }
        gcObject(hash);
    }

    // ── cache pin / evict ───────────────────────────────────────────────────────

    @Override
    public void pin(String key)   { setPinned(key, true); }

    @Override
    public void unpin(String key) { setPinned(key, false); }

    @Override
    public void evict(String key) throws IOException {
        delete(key);
    }

    @Override
    public int evictToFit(long targetBytes) throws IOException {
        long total = totalSize();
        if (total <= targetBytes) return 0;
        int evicted = 0;
        String sql = "SELECT key, size FROM vatn_blobs WHERE pinned=0 ORDER BY last_accessed_at ASC";
        List<String> toEvict = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next() && total > targetBytes) {
                toEvict.add(rs.getString("key"));
                total -= rs.getLong("size");
            }
        } catch (SQLException e) {
            throw new IOException("evictToFit query failed", e);
        }
        for (String key : toEvict) {
            delete(key);
            evicted++;
        }
        log.info("[BLOB] Evicted {} unpinned blobs to fit {} bytes", evicted, targetBytes);
        return evicted;
    }

    @Override
    public long totalSize() {
        // Physical bytes = sum of size over distinct content hashes (dedup-aware).
        String sql = "SELECT COALESCE(SUM(size),0) FROM (SELECT content_hash, MAX(size) AS size FROM vatn_blobs GROUP BY content_hash)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new RuntimeException("totalSize failed", e);
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private void upsert(String key, String hashHex, long size, String contentType) {
        String sql = """
            INSERT INTO vatn_blobs(key, content_hash, size, content_type, last_accessed_at)
            VALUES(?,?,?,?, strftime('%Y-%m-%dT%H:%M:%SZ','now'))
            ON CONFLICT(key) DO UPDATE SET
                content_hash=excluded.content_hash,
                size=excluded.size,
                content_type=excluded.content_type,
                last_accessed_at=excluded.last_accessed_at
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, hashHex);
            ps.setLong(3, size);
            ps.setString(4, contentType);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("blob upsert failed for " + key, e);
        }
    }

    private void setPinned(String key, boolean pinned) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE vatn_blobs SET pinned=? WHERE key=?")) {
            ps.setInt(1, pinned ? 1 : 0);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("pin/unpin failed for " + key, e);
        }
    }

    private void touch(String key) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE vatn_blobs SET last_accessed_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("[BLOB] touch failed for {}", key, e);
        }
    }

    private String lookupHash(String key) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT content_hash FROM vatn_blobs WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("lookup failed for " + key, e);
        }
    }

    private String requireHash(String key) throws IOException {
        String hash = lookupHash(key);
        if (hash == null) throw new IOException("No such blob: " + key);
        return hash;
    }

    /** Removes the physical object file if no remaining key references its hash. */
    private void gcObject(String hashHex) throws IOException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM vatn_blobs WHERE content_hash=?")) {
            ps.setString(1, hashHex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    Files.deleteIfExists(objectPath(hashHex));
                }
            }
        } catch (SQLException e) {
            throw new IOException("gc failed for hash " + hashHex, e);
        }
    }

    private Path objectPath(String hashHex) {
        return objectsDir.resolve(hashHex.substring(0, 2))
                         .resolve(hashHex.substring(2, 4))
                         .resolve(hashHex);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Instant parseInstant(String s) {
        try { return s == null ? null : Instant.parse(s); }
        catch (Exception e) { return null; }
    }

    private record Stored(String hashHex, long size) {}

    /** Caps the number of bytes readable from a delegate stream — for byte-range reads. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream in, long limit) { super(in); this.remaining = limit; }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
