package dev.vatn.core.blob;

import dev.vatn.api.VBlobStore;
import dev.vatn.core.memory.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalBlobStoreTest {

    @TempDir Path tempDir;
    private LocalBlobStore store;

    @BeforeEach
    void setUp() {
        DatabaseManager db = new DatabaseManager("jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath());
        db.registerSchemaContributor(new VatnBlobSchemaContributor());
        store = new LocalBlobStore(db, tempDir.resolve("blobs"));
    }

    @Test
    void contentAddressedPutDedupesAndReads() throws Exception {
        byte[] data = "hello vatn".getBytes(StandardCharsets.UTF_8);
        String k1 = store.putContent(data, "text/plain");
        String k2 = store.putContent(data, "text/plain");

        assertEquals(k1, k2, "identical content yields identical key");
        assertTrue(k1.startsWith("sha256:"));
        assertArrayEquals(data, store.get(k1));
        assertEquals(data.length, store.totalSize(), "dedup: stored once");
    }

    @Test
    void namedPutGetAndRangeRead() throws Exception {
        store.put("covers/42.txt", "0123456789".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertTrue(store.exists("covers/42.txt"));

        try (InputStream in = store.openRange("covers/42.txt", 2, 4)) {
            assertEquals("2345", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = store.openRange("covers/42.txt", 7, -1)) {
            assertEquals("789", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void pinExemptsFromEviction() throws Exception {
        store.put("a", new byte[100], "application/octet-stream");
        store.put("b", new byte[100], "application/octet-stream");
        store.pin("a");

        int evicted = store.evictToFit(50); // must drop below 50 bytes
        assertEquals(1, evicted);
        assertTrue(store.exists("a"), "pinned blob survives");
        assertFalse(store.exists("b"), "unpinned blob evicted");
    }

    @Test
    void deleteRemovesKey() throws Exception {
        store.put("x", new byte[]{1, 2, 3}, "application/octet-stream");
        store.delete("x");
        assertFalse(store.exists("x"));
        assertEquals(VBlobStore.class, VBlobStore.class); // type sanity
    }
}
