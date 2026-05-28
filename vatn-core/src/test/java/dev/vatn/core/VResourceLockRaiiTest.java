package dev.vatn.core;

import dev.vatn.api.VLock;
import dev.vatn.api.VResourceLockService;
import dev.vatn.core.memory.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class VResourceLockRaiiTest {

    @TempDir Path tempDir;
    private DatabaseManager db;
    private VResourceLockService locks;

    @BeforeEach
    void setUp() {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath();
        db = new DatabaseManager(jdbcUrl);
        locks = new VResourceLockServiceImpl(db);
    }

    @Test
    void tryAcquire_successWhenFree() {
        Optional<VLock> result = locks.tryAcquire("lock-a", Duration.ofMinutes(1));

        assertTrue(result.isPresent());
        VLock lock = result.get();
        assertTrue(lock.isHeld());
        assertEquals("lock-a", lock.name());

        lock.release();
    }

    @Test
    void tryAcquire_returnsEmptyWhenHeld() {
        VResourceLockService second = new VResourceLockServiceImpl(db);

        Optional<VLock> first = locks.tryAcquire("lock-b", Duration.ofMinutes(1));
        assertTrue(first.isPresent());

        Optional<VLock> attempt = second.tryAcquire("lock-b", Duration.ofMinutes(1));
        assertTrue(attempt.isEmpty(), "Second instance should not be able to acquire a held lock");

        first.get().release();
    }

    @Test
    void tryWithResources_releasesOnExit() {
        VResourceLockService second = new VResourceLockServiceImpl(db);

        Optional<VLock> lockOpt = locks.tryAcquire("lock-c", Duration.ofMinutes(1));
        assertTrue(lockOpt.isPresent());

        try (VLock lock = lockOpt.get()) {
            assertTrue(lock.isHeld());
        }

        Optional<VLock> afterRelease = second.tryAcquire("lock-c", Duration.ofMinutes(1));
        assertTrue(afterRelease.isPresent(), "Second instance should acquire the lock after try-with-resources releases it");

        afterRelease.get().release();
    }

    @Test
    void renew_extendsLockTtl() throws Exception {
        VLock lock = locks.tryAcquire("lock-d", Duration.ofSeconds(1)).orElseThrow();
        assertTrue(lock.isHeld());

        boolean renewed = lock.renew(Duration.ofSeconds(60));
        assertTrue(renewed);
        assertTrue(lock.isHeld());

        Thread.sleep(1500);

        assertTrue(lock.isHeld(), "Lock should still be held after renewal extended TTL");

        lock.release();
    }

    @Test
    void isHeld_falseAfterRelease() {
        VLock lock = locks.tryAcquire("lock-e", Duration.ofMinutes(1)).orElseThrow();
        assertTrue(lock.isHeld());

        lock.release();

        assertFalse(lock.isHeld());
    }

    @Test
    void acquire_blocking_waitsForRelease() throws Exception {
        VResourceLockService second = new VResourceLockServiceImpl(db);

        VLock held = locks.tryAcquire("lock-f", Duration.ofMinutes(1)).orElseThrow();

        CountDownLatch releasedLatch = new CountDownLatch(1);
        AtomicBoolean secondAcquired = new AtomicBoolean(false);

        Thread acquirer = Thread.ofVirtual().start(() -> {
            try {
                releasedLatch.await();
                VLock secondLock = second.acquire("lock-f", Duration.ofMinutes(1), Duration.ofSeconds(3));
                secondAcquired.set(true);
                secondLock.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(200);
        held.release();
        releasedLatch.countDown();

        acquirer.join(3000);
        assertTrue(secondAcquired.get(), "Second instance should have acquired the lock after release");
    }

    @Test
    void acquire_throwsOnTimeout() {
        VResourceLockService second = new VResourceLockServiceImpl(db);

        VLock held = locks.tryAcquire("lock-g", Duration.ofMinutes(1)).orElseThrow();

        assertThrows(IllegalStateException.class, () ->
            second.acquire("lock-g", Duration.ofMinutes(1), Duration.ofMillis(200)),
            "acquire should throw when lock cannot be obtained within waitTimeout"
        );

        held.release();
    }
}
