package dev.vatn.core;

import dev.vatn.api.VSubprocessAuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class VSubprocessAuditServiceTest {

    private VSubprocessAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VSubprocessAuditServiceImpl();
    }

    // ── record() + getAll() ───────────────────────────────────────────────────

    @Test
    void recordAndGetAll_returnsAllEntries() {
        service.record(entry("sess-1", "echo hello",   0, 12));
        service.record(entry("sess-2", "ls -la",       0, 5));
        service.record(entry("sess-1", "cat /etc/hosts", 0, 8));

        List<VSubprocessAuditEntry> all = service.getAll();
        assertEquals(3, all.size());
        assertEquals("echo hello",    all.get(0).command());
        assertEquals("ls -la",        all.get(1).command());
        assertEquals("cat /etc/hosts", all.get(2).command());
    }

    @Test
    void getAll_returnsUnmodifiableSnapshot() {
        service.record(entry("s", "cmd", 0, 1));
        List<VSubprocessAuditEntry> snapshot = service.getAll();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(entry("x", "y", 0, 0)));
    }

    @Test
    void getAll_emptyWhenNothingRecorded() {
        assertTrue(service.getAll().isEmpty());
    }

    // ── getForSession() ───────────────────────────────────────────────────────

    @Test
    void getForSession_returnsOnlyMatchingSession() {
        service.record(entry("sess-A", "cmd1", 0, 10));
        service.record(entry("sess-B", "cmd2", 1, 20));
        service.record(entry("sess-A", "cmd3", 0, 15));

        List<VSubprocessAuditEntry> forA = service.getForSession("sess-A");
        assertEquals(2, forA.size());
        assertTrue(forA.stream().allMatch(e -> "sess-A".equals(e.sessionId())));
    }

    @Test
    void getForSession_emptyForUnknownSession() {
        service.record(entry("sess-X", "cmd", 0, 5));
        assertTrue(service.getForSession("unknown").isEmpty());
    }

    @Test
    void getForSession_handlesNullSessionId() {
        service.record(entry("sess-X", "cmd", 0, 5));
        assertTrue(service.getForSession(null).isEmpty());
    }

    // ── size() ────────────────────────────────────────────────────────────────

    @Test
    void size_reflectsRecordCount() {
        assertEquals(0, service.size());
        service.record(entry("s", "a", 0, 1));
        assertEquals(1, service.size());
        service.record(entry("s", "b", 0, 2));
        assertEquals(2, service.size());
    }

    // ── toJsonArray() ─────────────────────────────────────────────────────────

    @Test
    void toJsonArray_emptyReturnsEmptyArray() {
        assertEquals("[]", service.toJsonArray());
    }

    @Test
    void toJsonArray_singleEntryIsValidJson() {
        service.record(entry("sess-1", "echo hi", 0, 42));
        String json = service.toJsonArray();
        assertTrue(json.startsWith("[{"));
        assertTrue(json.endsWith("}]"));
        assertTrue(json.contains("\"sessionId\":\"sess-1\""));
        assertTrue(json.contains("\"command\":\"echo hi\""));
        assertTrue(json.contains("\"exitCode\":0"));
        assertTrue(json.contains("\"durationMs\":42"));
    }

    @Test
    void toJsonArray_multipleEntriesAreCommaSeparated() {
        service.record(entry("s", "cmd1", 0, 1));
        service.record(entry("s", "cmd2", 1, 2));
        String json = service.toJsonArray();
        // Should have exactly one separating comma between the two objects
        int commaCount = (int) json.chars().filter(c -> c == '}').count();
        assertEquals(2, commaCount, "should contain exactly two JSON objects");
    }

    // ── VSubprocessAuditEntry.toJson() ────────────────────────────────────────

    @Test
    void entryToJson_escapesDoubleQuotesInCommand() {
        VSubprocessAuditEntry e = entry("s", "echo \"hello world\"", 0, 5);
        String json = e.toJson();
        assertTrue(json.contains("\\\"hello world\\\""), "quotes must be escaped: " + json);
    }

    @Test
    void entryToJson_escapesNewlinesInCommand() {
        VSubprocessAuditEntry e = entry("s", "echo line1\necho line2", 0, 5);
        String json = e.toJson();
        assertFalse(json.contains("\n"), "literal newlines must be escaped");
        assertTrue(json.contains("\\n"));
    }

    @Test
    void entryToJson_nonZeroExitCode() {
        VSubprocessAuditEntry e = entry("s", "false", 1, 3);
        assertTrue(e.toJson().contains("\"exitCode\":1"));
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    void concurrent_recordsAreAllPresent() throws InterruptedException {
        int threads   = 20;
        int perThread = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        service.record(entry("thread-" + tid, "cmd-" + i, 0, i));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        done.await();
        pool.shutdownNow();

        assertEquals(threads * perThread, service.size(),
            "all concurrent records must be persisted");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static VSubprocessAuditEntry entry(String sessionId, String cmd, int exit, long ms) {
        return new VSubprocessAuditEntry(sessionId, cmd, exit, ms, Instant.now());
    }
}
