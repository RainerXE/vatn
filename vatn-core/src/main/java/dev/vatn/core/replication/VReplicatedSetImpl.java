package dev.vatn.core.replication;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.replication.VChange;
import dev.vatn.api.replication.VConflictResolver;
import dev.vatn.api.replication.VReplicatedSet;
import dev.vatn.api.replication.VReplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQLite-backed implementation of a {@link VReplicatedSet}: a materialised key/value store
 * ({@code vatn_repl_data}) fed by an append-only change feed ({@code vatn_repl_feed}), with
 * per-peer watermarks ({@code vatn_repl_watermark}). The per-set version counter is a Lamport clock
 * recovered from the feed on startup, guaranteeing monotonic, deterministic conflict resolution.
 */
public class VReplicatedSetImpl implements VReplicatedSet {

    private static final Logger log = LoggerFactory.getLogger(VReplicatedSetImpl.class);

    private final String name;
    private final String localNodeId;
    private final VPersistenceService db;
    private final VConflictResolver resolver;
    private final Runnable syncTrigger;        // wired by the service to its sync loop

    private final AtomicLong lamport = new AtomicLong(0);
    private final AtomicLong changesSent = new AtomicLong(0);
    private final AtomicLong changesReceived = new AtomicLong(0);
    private final AtomicLong conflictsResolved = new AtomicLong(0);
    private volatile Instant lastSyncAt;

    public VReplicatedSetImpl(VReplicationConfig config, String localNodeId,
                              VPersistenceService db, Runnable syncTrigger) {
        this.name = config.name();
        this.localNodeId = localNodeId;
        this.db = db;
        this.resolver = config.conflictResolver() != null
                ? config.conflictResolver() : VConflictResolver.lastWriteWins();
        this.syncTrigger = syncTrigger;
        this.lamport.set(recoverMaxVersion());
    }

    @Override public String name() { return name; }

    // ── local writes ─────────────────────────────────────────────────────────

    @Override
    public void put(String key, byte[] value) {
        long version = lamport.incrementAndGet();
        VChange change = new VChange(name, key, value, version, localNodeId, Instant.now(), false);
        materialiseAndAppend(change);
    }

    @Override
    public void delete(String key) {
        long version = lamport.incrementAndGet();
        VChange change = new VChange(name, key, null, version, localNodeId, Instant.now(), true);
        materialiseAndAppend(change);
    }

    // ── local reads ──────────────────────────────────────────────────────────

    @Override
    public Optional<byte[]> get(String key) {
        String sql = "SELECT value, tombstone FROM vatn_repl_data WHERE set_name=? AND key=?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt("tombstone") != 0) return Optional.empty();
                return Optional.ofNullable(rs.getBytes("value"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("get failed for " + key, e);
        }
    }

    @Override
    public List<String> keys(String prefix) {
        List<String> keys = new ArrayList<>();
        String sql = "SELECT key FROM vatn_repl_data WHERE set_name=? AND tombstone=0 AND key LIKE ? ORDER BY key";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("keys failed", e);
        }
        return keys;
    }

    // ── replication state ──────────────────────────────────────────────────────

    @Override
    public long feedHead() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(seq),0) FROM vatn_repl_feed WHERE set_name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        } catch (SQLException e) {
            throw new RuntimeException("feedHead failed", e);
        }
    }

    @Override
    public long watermark(String peerNodeId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT watermark FROM vatn_repl_watermark WHERE set_name=? AND peer_node=?")) {
            ps.setString(1, name);
            ps.setString(2, peerNodeId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        } catch (SQLException e) {
            throw new RuntimeException("watermark read failed", e);
        }
    }

    void setWatermark(String peerNodeId, long value) {
        String sql = """
            INSERT INTO vatn_repl_watermark(set_name, peer_node, watermark) VALUES(?,?,?)
            ON CONFLICT(set_name, peer_node) DO UPDATE SET watermark=excluded.watermark
            WHERE excluded.watermark > vatn_repl_watermark.watermark
            """;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, peerNodeId);
            ps.setLong(3, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("watermark write failed", e);
        }
    }

    @Override
    public List<VChange> changesSince(long afterOffset, int limit) {
        List<VChange> out = new ArrayList<>();
        String sql = "SELECT key, value, version, origin_node, updated_at, tombstone "
                + "FROM vatn_repl_feed WHERE set_name=? AND seq>? ORDER BY seq ASC LIMIT ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setLong(2, afterOffset);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean tomb = rs.getInt("tombstone") != 0;
                    out.add(new VChange(name, rs.getString("key"),
                            tomb ? null : rs.getBytes("value"),
                            rs.getLong("version"), rs.getString("origin_node"),
                            parseInstant(rs.getString("updated_at")), tomb));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("changesSince failed", e);
        }
        return out;
    }

    /** Highest feed seq among rows with seq in (afterOffset, ...] limited to {@code limit}. */
    long maxSeqInWindow(long afterOffset, int limit) {
        String sql = "SELECT COALESCE(MAX(seq), ?) FROM (SELECT seq FROM vatn_repl_feed "
                + "WHERE set_name=? AND seq>? ORDER BY seq ASC LIMIT ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, afterOffset);
            ps.setString(2, name);
            ps.setLong(3, afterOffset);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : afterOffset; }
        } catch (SQLException e) {
            throw new RuntimeException("maxSeqInWindow failed", e);
        }
    }

    @Override
    public int applyInbound(String fromNodeId, List<VChange> changes, long throughOffset) {
        int applied = 0;
        for (VChange incoming : changes) {
            // Bump the Lamport clock so future local writes outrank what we have seen.
            lamport.updateAndGet(cur -> Math.max(cur, incoming.version()));
            changesReceived.incrementAndGet();

            VChange local = currentChange(incoming.key());
            if (local == null) {
                materialiseAndAppend(rebind(incoming));
                applied++;
                continue;
            }
            // Idempotent: already have this exact authored version.
            if (local.version() == incoming.version()
                    && local.originNodeId().equals(incoming.originNodeId())) {
                continue;
            }
            VChange winner = resolver.resolve(local, incoming);
            conflictsResolved.incrementAndGet();
            if (winner == incoming) {
                materialiseAndAppend(rebind(incoming));
                applied++;
            }
        }
        if (throughOffset > 0) setWatermark(fromNodeId, throughOffset);
        lastSyncAt = Instant.now();
        return applied;
    }

    @Override public void syncNow() { if (syncTrigger != null) syncTrigger.run(); }

    @Override
    public Stats stats() {
        return new Stats(changesSent.get(), changesReceived.get(), conflictsResolved.get(),
                feedHead(), lastSyncAt);
    }

    @Override public void close() { /* sync lifecycle owned by the service */ }

    void incrementSent(long n) { changesSent.addAndGet(n); }

    void markSynced() { lastSyncAt = Instant.now(); }

    // ── internals ───────────────────────────────────────────────────────────────

    /** Re-creates the change with the same authoring identity (used when applying inbound). */
    private VChange rebind(VChange in) {
        return new VChange(name, in.key(), in.value(), in.version(), in.originNodeId(),
                in.timestamp() != null ? in.timestamp() : Instant.now(), in.tombstone());
    }

    private VChange currentChange(String key) {
        String sql = "SELECT value, version, origin_node, updated_at, tombstone "
                + "FROM vatn_repl_data WHERE set_name=? AND key=?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                boolean tomb = rs.getInt("tombstone") != 0;
                return new VChange(name, key, tomb ? null : rs.getBytes("value"),
                        rs.getLong("version"), rs.getString("origin_node"),
                        parseInstant(rs.getString("updated_at")), tomb);
            }
        } catch (SQLException e) {
            throw new RuntimeException("currentChange failed for " + key, e);
        }
    }

    /** Atomically materialises a change into the data table and appends it to the feed. */
    private void materialiseAndAppend(VChange change) {
        String upsertData = """
            INSERT INTO vatn_repl_data(set_name, key, value, version, origin_node, updated_at, tombstone)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(set_name, key) DO UPDATE SET
                value=excluded.value, version=excluded.version, origin_node=excluded.origin_node,
                updated_at=excluded.updated_at, tombstone=excluded.tombstone
            """;
        String appendFeed = """
            INSERT INTO vatn_repl_feed(set_name, key, value, version, origin_node, updated_at, tombstone)
            VALUES(?,?,?,?,?,?,?)
            """;
        String ts = change.timestamp() != null ? change.timestamp().toString() : Instant.now().toString();
        try (Connection c = db.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement d = c.prepareStatement(upsertData);
                 PreparedStatement f = c.prepareStatement(appendFeed)) {
                bindChange(d, change, ts);
                d.executeUpdate();
                bindChange(f, change, ts);
                f.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            throw new RuntimeException("materialise failed for " + change.key(), e);
        }
    }

    private void bindChange(PreparedStatement ps, VChange c, String ts) throws SQLException {
        ps.setString(1, name);
        ps.setString(2, c.key());
        ps.setBytes(3, c.tombstone() ? null : c.value());
        ps.setLong(4, c.version());
        ps.setString(5, c.originNodeId());
        ps.setString(6, ts);
        ps.setInt(7, c.tombstone() ? 1 : 0);
    }

    private long recoverMaxVersion() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(version),0) FROM vatn_repl_feed WHERE set_name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        } catch (SQLException e) {
            log.warn("[REPL] Could not recover version clock for set '{}'", name, e);
            return 0L;
        }
    }

    private static Instant parseInstant(String s) {
        try { return s == null ? Instant.now() : Instant.parse(s); }
        catch (Exception e) { return Instant.now(); }
    }
}
