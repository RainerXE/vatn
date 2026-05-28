package dev.vatn.api;

import dev.vatn.api.VatnApi;

import java.time.Duration;

/**
 * RAII handle for an advisory lock acquired via {@link VResourceLockService}.
 *
 * <p>Implements {@link AutoCloseable} so locks are automatically released at the end of a
 * try-with-resources block, even if an exception is thrown:
 *
 * <pre>{@code
 * VResourceLockService locks = ctx.getService(VResourceLockService.class).orElseThrow();
 *
 * locks.tryAcquire("report-generator", Duration.ofMinutes(5)).ifPresent(lock -> {
 *     try (lock) {
 *         generateReport();   // lock released when block exits, or on crash after TTL
 *     }
 * });
 * }</pre>
 *
 * <p>Locks are advisory and TTL-protected: a crashed holder's lock expires automatically
 * after the TTL, so other nodes can acquire it without manual intervention.
 */
@VatnApi(since = "1.0-alpha.9")
public interface VLock extends AutoCloseable {

    /** The lock name this handle was acquired for. */
    String name();

    /** Returns true if this handle currently holds the lock (has not been released or expired). */
    boolean isHeld();

    /**
     * Extends the lock's TTL by {@code additional} from now.
     * Returns false if the lock was lost (expired or released by another path).
     * Call periodically from long-running workers to prevent inadvertent expiry.
     */
    boolean renew(Duration additional);

    /** Releases the lock immediately, before the TTL expires. */
    void release();

    /** Alias for {@link #release()} — enables try-with-resources. */
    @Override
    default void close() { release(); }
}
