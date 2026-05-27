package dev.vatn.core.workflow;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.VPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Pool slot manager — tracks in-use slots per named pool using in-memory semaphores,
 * with pool definitions persisted in {@code vatn_pool}.
 */
public class VPoolManagerImpl {
    private static final Logger logger = LoggerFactory.getLogger(VPoolManagerImpl.class);

    private final VPersistenceService db;
    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public VPoolManagerImpl(VPersistenceService db) {
        this.db = db;
        loadPools();
    }

    private void loadPools() {
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement("SELECT pool_name, slots FROM vatn_pool");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("pool_name");
                int slots = rs.getInt("slots");
                semaphores.put(name, new Semaphore(slots, true));
            }
        } catch (Exception e) {
            logger.error("[POOL] Failed to load pools", e);
            semaphores.put(VPool.DEFAULT_POOL, new Semaphore(VPool.DEFAULT_POOL_SLOTS, true));
        }
    }

    public void registerPool(VPool pool) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO vatn_pool(pool_name, slots, description)
                VALUES(?, ?, ?)
                """)) {
            ps.setString(1, pool.name());
            ps.setInt(2, pool.slots());
            ps.setString(3, pool.description());
            ps.executeUpdate();
            semaphores.put(pool.name(), new Semaphore(pool.slots(), true));
            logger.info("[POOL] Registered pool '{}' with {} slots", pool.name(), pool.slots());
        } catch (Exception e) {
            logger.error("[POOL] Failed to register pool: {}", pool.name(), e);
        }
    }

    /** Acquires a slot, blocking until one is available. Returns false if pool not found. */
    public boolean acquireSlot(String poolName) {
        Semaphore sem = semaphores.computeIfAbsent(poolName,
            k -> new Semaphore(VPool.DEFAULT_POOL_SLOTS, true));
        try {
            sem.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Releases a previously acquired slot. */
    public void releaseSlot(String poolName) {
        Semaphore sem = semaphores.get(poolName);
        if (sem != null) sem.release();
    }

    /** Returns true if a slot is available without acquiring it. */
    public boolean hasAvailableSlot(String poolName) {
        Semaphore sem = semaphores.get(poolName);
        return sem == null || sem.availablePermits() > 0;
    }
}
