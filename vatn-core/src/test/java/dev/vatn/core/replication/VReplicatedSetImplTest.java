package dev.vatn.core.replication;

import dev.vatn.api.replication.VChange;
import dev.vatn.api.replication.VConflictResolver;
import dev.vatn.api.replication.VReplicationConfig;
import dev.vatn.core.memory.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VReplicatedSetImplTest {

    @TempDir Path tempDir;
    private DatabaseManager db;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager("jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath());
        db.registerSchemaContributor(new VatnReplicationSchemaContributor());
    }

    private VReplicatedSetImpl set(String node) {
        return new VReplicatedSetImpl(VReplicationConfig.of("library"), node, db, () -> {});
    }

    @Test
    void localPutIsReadableAndFeeds() {
        VReplicatedSetImpl s = set("node-a");
        s.put("book/1", "Dune".getBytes(StandardCharsets.UTF_8));
        assertEquals("Dune", new String(s.get("book/1").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(1, s.changesSince(0, 100).size());
        assertTrue(s.feedHead() > 0);
    }

    @Test
    void deleteTombstonesAndHidesValue() {
        VReplicatedSetImpl s = set("node-a");
        s.put("book/1", "x".getBytes());
        s.delete("book/1");
        assertTrue(s.get("book/1").isEmpty());
        // both the put and the tombstone are on the feed
        assertEquals(2, s.changesSince(0, 100).size());
    }

    @Test
    void inboundHigherVersionWinsLww() {
        VReplicatedSetImpl s = set("node-a");
        s.put("book/1", "local".getBytes());          // version 1 by node-a

        VChange remote = new VChange("library", "book/1", "remote".getBytes(),
                99, "node-b", Instant.now(), false);  // higher version
        int applied = s.applyInbound("node-b", List.of(remote), 5);

        assertEquals(1, applied);
        assertEquals("remote", new String(s.get("book/1").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(5, s.watermark("node-b"), "watermark advanced");
    }

    @Test
    void inboundLowerVersionLoses() {
        VReplicatedSetImpl s = set("node-a");
        s.put("book/1", "local".getBytes());          // version 1
        s.put("book/1", "local2".getBytes());         // version 2

        VChange stale = new VChange("library", "book/1", "old".getBytes(),
                1, "node-b", Instant.now().minusSeconds(60), false);
        int applied = s.applyInbound("node-b", List.of(stale), 3);

        assertEquals(0, applied, "stale change loses LWW");
        assertEquals("local2", new String(s.get("book/1").orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void inboundIsIdempotent() {
        VReplicatedSetImpl s = set("node-a");
        VChange c = new VChange("library", "book/1", "v".getBytes(), 7, "node-b", Instant.now(), false);
        assertEquals(1, s.applyInbound("node-b", List.of(c), 7));
        assertEquals(0, s.applyInbound("node-b", List.of(c), 7), "re-applying same change is a no-op");
    }

    @Test
    void lastWriteWinsResolverIsDeterministic() {
        VConflictResolver r = VConflictResolver.lastWriteWins();
        VChange a = new VChange("s", "k", null, 5, "node-a", Instant.ofEpochMilli(1000), false);
        VChange b = new VChange("s", "k", null, 5, "node-b", Instant.ofEpochMilli(1000), false);
        // same version + timestamp → higher origin id wins, deterministically
        assertEquals(b, r.resolve(a, b));
        assertEquals(b, r.resolve(b, a));
    }
}
