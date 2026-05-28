package dev.vatn.core.messaging;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VTopic;
import dev.vatn.api.VTopicEvent;
import dev.vatn.api.VTopicSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class VTopicImpl implements VTopic {
    private static final Logger log = LoggerFactory.getLogger(VTopicImpl.class);

    private final String name;
    private final VPersistenceService db;

    VTopicImpl(String name, VPersistenceService db) {
        this.name = name;
        this.db = db;
    }

    @Override
    public String name() { return name; }

    // ── Publish ───────────────────────────────────────────────────────────────

    @Override
    public long publish(String payload) {
        try (Connection conn = db.getConnection()) {
            return insertEvent(conn, payload);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to publish to topic " + name, e);
        }
    }

    @Override
    public long publish(String payload, Connection tx) {
        try {
            return insertEvent(tx, payload);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to publish to topic " + name, e);
        }
    }

    private long insertEvent(Connection conn, String payload) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO vatn_topic_events (topic, payload) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, payload);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No generated key returned for topic event");
        }
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    @Override
    public VTopicSubscription subscribe(String consumerId, EventHandler handler) {
        long savedOffset = getOffset(consumerId);
        VTopicSubscriptionImpl sub = new VTopicSubscriptionImpl(this, consumerId, savedOffset, db);
        sub.start(handler);
        return sub;
    }

    // ── Offset management ────────────────────────────────────────────────────

    @Override
    public void seek(String consumerId, long offset) {
        saveOffset(consumerId, offset);
    }

    @Override
    public long getOffset(String consumerId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT offset FROM vatn_topic_offsets WHERE topic=? AND consumer_id=?")) {
            ps.setString(1, name);
            ps.setString(2, consumerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("offset") : 0L;
            }
        } catch (SQLException e) {
            log.error("[VTopic-{}] getOffset failed: {}", name, e.getMessage());
            return 0L;
        }
    }

    @Override
    public long latestOffset() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(id) FROM vatn_topic_events WHERE topic=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return (rs.next() && rs.getObject(1) != null) ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            return 0L;
        }
    }

    // ── Read / prune ──────────────────────────────────────────────────────────

    @Override
    public List<VTopicEvent> read(long afterOffset, int limit) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT id, topic, payload, published_at
                FROM vatn_topic_events
                WHERE topic=? AND id > ?
                ORDER BY id ASC
                LIMIT ?
                """)) {
            ps.setString(1, name);
            ps.setLong(2, afterOffset);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<VTopicEvent> events = new ArrayList<>();
                while (rs.next()) events.add(mapEvent(rs));
                return events;
            }
        } catch (SQLException e) {
            log.error("[VTopic-{}] read failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    @Override
    public int prune(long beforeOffset) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM vatn_topic_events WHERE topic=? AND id <= ?")) {
            ps.setString(1, name);
            ps.setLong(2, beforeOffset);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[VTopic-{}] prune failed: {}", name, e.getMessage());
            return 0;
        }
    }

    // ── Package-internal helpers ──────────────────────────────────────────────

    void saveOffset(String consumerId, long offset) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO vatn_topic_offsets (topic, consumer_id, offset, updated_at)
                VALUES (?, ?, ?, strftime('%Y-%m-%dT%H:%M:%SZ','now'))
                ON CONFLICT(topic, consumer_id)
                DO UPDATE SET offset=excluded.offset, updated_at=excluded.updated_at
                """)) {
            ps.setString(1, name);
            ps.setString(2, consumerId);
            ps.setLong(3, offset);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[VTopic-{}] saveOffset failed: {}", name, e.getMessage());
        }
    }

    List<VTopicEvent> readBatch(long afterOffset) {
        return read(afterOffset, 200);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private static VTopicEvent mapEvent(ResultSet rs) throws SQLException {
        String publishedAtStr = rs.getString("published_at");
        Instant publishedAt = publishedAtStr != null ? Instant.parse(publishedAtStr) : Instant.now();
        return new VTopicEvent(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getString("payload"),
            publishedAt
        );
    }
}
