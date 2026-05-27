package dev.vatn.core.workflow;

import dev.vatn.api.workflow.VPool;
import dev.vatn.core.memory.DatabaseManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VPoolManagerTest {

    @TempDir Path tempDir;
    private VPoolManagerImpl poolManager;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("pools.db").toAbsolutePath();
        DatabaseManager db = new DatabaseManager(jdbcUrl);
        db.registerSchemaContributor(new VatnWorkflowSchemaContributor());
        poolManager = new VPoolManagerImpl(db);
    }

    @Test
    void defaultPool_hasAvailableSlot() {
        assertTrue(poolManager.hasAvailableSlot(VPool.DEFAULT_POOL),
                "default_pool must have at least one available slot at startup");
    }

    @Test
    void acquireSlot_thenHasNoSlotInSmallPool() {
        poolManager.registerPool(new VPool("one-slot", 1, "single-slot test pool"));

        assertTrue(poolManager.hasAvailableSlot("one-slot"));
        poolManager.acquireSlot("one-slot");
        assertFalse(poolManager.hasAvailableSlot("one-slot"), "Slot must be consumed after acquireSlot");

        poolManager.releaseSlot("one-slot");
        assertTrue(poolManager.hasAvailableSlot("one-slot"), "Slot must be available after releaseSlot");
    }

    @Test
    void registerPool_newPool_acquireAndRelease() {
        poolManager.registerPool(new VPool("test-pool", 4, "test pool for unit tests"));
        assertTrue(poolManager.hasAvailableSlot("test-pool"));

        // Drain all 4 slots
        poolManager.acquireSlot("test-pool");
        poolManager.acquireSlot("test-pool");
        poolManager.acquireSlot("test-pool");
        poolManager.acquireSlot("test-pool");
        assertFalse(poolManager.hasAvailableSlot("test-pool"), "Pool should be exhausted");

        // Release one — slot becomes available again
        poolManager.releaseSlot("test-pool");
        assertTrue(poolManager.hasAvailableSlot("test-pool"), "One released slot must become available");
    }

    @Test
    void smallPool_blocksWhenExhausted() throws Exception {
        poolManager.registerPool(new VPool("tiny-pool", 2, "only 2 slots"));

        // Drain both slots
        poolManager.acquireSlot("tiny-pool");
        poolManager.acquireSlot("tiny-pool");
        assertFalse(poolManager.hasAvailableSlot("tiny-pool"));

        // Third acquire should block — verify it unblocks after a release
        AtomicInteger completed = new AtomicInteger(0);
        Future<?> blocked = Executors.newSingleThreadExecutor().submit(() -> {
            poolManager.acquireSlot("tiny-pool"); // should block
            completed.incrementAndGet();
        });

        Thread.sleep(100); // give it time to block
        assertEquals(0, completed.get(), "acquireSlot must block when pool is exhausted");

        poolManager.releaseSlot("tiny-pool"); // unblock it
        blocked.get(2, TimeUnit.SECONDS);
        assertEquals(1, completed.get(), "acquireSlot must proceed once a slot is released");
    }

    @Test
    void concurrentAcquires_neverExceedSlots() throws Exception {
        int slots = 8;
        poolManager.registerPool(new VPool("concurrent-pool", slots, "concurrency test"));

        int threads = 20;
        AtomicInteger peak = new AtomicInteger(0);
        AtomicInteger active = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    poolManager.acquireSlot("concurrent-pool");
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    Thread.sleep(10);
                    active.decrementAndGet();
                    poolManager.releaseSlot("concurrent-pool");
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        exec.shutdown();

        assertTrue(peak.get() <= slots,
                "Peak concurrency " + peak.get() + " must not exceed pool size " + slots);
    }
}
