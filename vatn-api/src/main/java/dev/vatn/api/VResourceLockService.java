package dev.vatn.api;

import java.time.Duration;
import java.util.Optional;

/**
 * Advisory lock service for coordinating exclusive access across plugins and nodes.
 *
 * <p>Locks are TTL-protected and stored in the node's SQLite database. A crashed holder's
 * lock expires automatically after the TTL — no manual cleanup required.
 *
 * <h3>Low-level (legacy) API</h3>
 * <pre>{@code
 * locks.tryLock("backup", 60);   // returns boolean
 * locks.unlock("backup");
 * }</pre>
 *
 * <h3>RAII API — preferred</h3>
 * <pre>{@code
 * // non-blocking
 * locks.tryAcquire("report-generator", Duration.ofMinutes(5)).ifPresent(lock -> {
 *     try (lock) { generateReport(); }
 * });
 *
 * // blocking — waits up to 10 s for the lock to become available
 * try (VLock lock = locks.acquire("db-migration", Duration.ofMinutes(2), Duration.ofSeconds(10))) {
 *     runMigration();
 * }
 * }</pre>
 *
 * <p>Also used internally by the DAG scheduler for leader election: only the node that holds
 * the {@code vatn.scheduler} lock fires cron DAGs, preventing double-firing in a cluster.
 */
@VatnApi(since = "1.0")
public interface VResourceLockService extends VService {

    // ── Legacy boolean API (kept for backward compatibility) ──────────────────

    /**
     * Tries to acquire a lock. Returns true if acquired, false if already held by another owner.
     * @param timeoutSeconds TTL in seconds; the lock auto-expires after this duration.
     */
    boolean tryLock(String resourceId, long timeoutSeconds);

    /** Releases a lock previously acquired by this service instance. */
    void unlock(String resourceId);

    // ── RAII handle API ───────────────────────────────────────────────────────

    /**
     * Non-blocking: tries to acquire {@code name} with the given TTL.
     * Returns an empty Optional if the lock is already held by another owner.
     */
    default Optional<VLock> tryAcquire(String name, Duration ttl) {
        boolean ok = tryLock(name, ttl.toSeconds());
        if (!ok) return Optional.empty();
        return Optional.of(new VLock() {
            private volatile boolean held = true;
            @Override public String name()  { return name; }
            @Override public boolean isHeld() { return held; }
            @Override public boolean renew(Duration additional) {
                boolean r = tryLock(name, additional.toSeconds());
                if (!r) held = false;
                return r;
            }
            @Override public void release() {
                if (held) { unlock(name); held = false; }
            }
        });
    }

    /**
     * Blocking: waits up to {@code waitTimeout} for the lock to become available, then
     * acquires it with {@code ttl}. Throws {@link IllegalStateException} if {@code waitTimeout}
     * elapses without acquiring.
     *
     * <p>Safe to call from a virtual thread — uses {@link Thread#sleep} between retries.
     */
    default VLock acquire(String name, Duration ttl, Duration waitTimeout) {
        long deadline = System.currentTimeMillis() + waitTimeout.toMillis();
        long retryMs  = 50;
        while (System.currentTimeMillis() < deadline) {
            Optional<VLock> lock = tryAcquire(name, ttl);
            if (lock.isPresent()) return lock.get();
            try { Thread.sleep(retryMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            retryMs = Math.min(retryMs * 2, 500);
        }
        throw new IllegalStateException("Could not acquire lock '" + name + "' within " + waitTimeout);
    }
}
