package dev.vatn.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VSchedulerTest {

    private VSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new VSchedulerImpl();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void everyFiresRepeatedly() throws Exception {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);
        scheduler.every("tick", Duration.ofMillis(50), () -> {
            count.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS), "should fire 3 times within 1s");
        assertTrue(count.get() >= 3);
        assertTrue(scheduler.scheduled().contains("tick"));
    }

    @Test
    void cancelStopsFiring() throws Exception {
        AtomicInteger count = new AtomicInteger();
        scheduler.every("cancellable", Duration.ofMillis(50), count::incrementAndGet);
        Thread.sleep(120);
        scheduler.cancel("cancellable");
        int snapshot = count.get();
        Thread.sleep(150);
        assertEquals(snapshot, count.get(), "no more fires after cancel");
        assertFalse(scheduler.scheduled().contains("cancellable"));
    }

    @Test
    void reschedulingReplacesPrevious() throws Exception {
        AtomicInteger first  = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        scheduler.every("task", Duration.ofMillis(50), first::incrementAndGet);
        Thread.sleep(80);
        scheduler.every("task", Duration.ofMillis(50), second::incrementAndGet); // replaces
        Thread.sleep(200);
        assertTrue(second.get() > 0, "replacement task fired");
        // first task fires before replacement; after replacement only second fires
        int firstCount = first.get();
        Thread.sleep(100);
        assertEquals(firstCount, first.get(), "old task is stopped");
    }

    @Test
    void nextRunIsPopulatedForEvery() throws Exception {
        scheduler.every("future", Duration.ofMinutes(10), () -> {});
        Optional<Instant> next = scheduler.nextRun("future");
        assertTrue(next.isPresent());
        assertTrue(next.get().isAfter(Instant.now()));
    }

    @Test
    void skipOnOverlapPreventsConccurentRuns() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        scheduler.every("slow", Duration.ofMillis(30), () -> {
            int c = concurrent.incrementAndGet();
            maxConcurrent.updateAndGet(v -> Math.max(v, c));
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            concurrent.decrementAndGet();
            done.countDown();
        });

        done.await(1, TimeUnit.SECONDS);
        assertEquals(1, maxConcurrent.get(), "overlapping runs must be skipped");
    }

    @Test
    void cronInvalidExpressionThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.cron("bad", "* * * *", () -> {}), // only 4 fields
                "should reject invalid cron");
    }

    @Test
    void scheduledListIsAccurate() {
        scheduler.every("a", Duration.ofSeconds(1), () -> {});
        scheduler.every("b", Duration.ofSeconds(1), () -> {});
        assertTrue(scheduler.scheduled().containsAll(java.util.List.of("a", "b")));
        scheduler.cancel("a");
        assertFalse(scheduler.scheduled().contains("a"));
        assertTrue(scheduler.scheduled().contains("b"));
    }
}
