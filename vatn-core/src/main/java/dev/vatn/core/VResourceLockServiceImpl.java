package dev.vatn.core;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VResourceLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Implementation of VResourceLockService using SQLite for multi-process coordination.
 */
public class VResourceLockServiceImpl implements VResourceLockService {
    private static final Logger logger = LoggerFactory.getLogger(VResourceLockServiceImpl.class);
    private final VPersistenceService db;
    private final String ownerId;

    public VResourceLockServiceImpl(VPersistenceService db) {
        this.db = db;
        this.ownerId = UUID.randomUUID().toString();
    }

    @Override
    public boolean tryLock(String resourceId, long timeoutSeconds) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long now = Instant.now().getEpochSecond();
                
                String checkSql = "SELECT owner_id, expires_at FROM vatn_resource_locks WHERE resource_id = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(checkSql)) {
                    pstmt.setString(1, resourceId);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            String currentOwner = rs.getString("owner_id");
                            long expiresAt = rs.getLong("expires_at");
                            
                            if (currentOwner.equals(ownerId)) {
                                renewLock(conn, resourceId, timeoutSeconds);
                                conn.commit();
                                return true;
                            }
                            
                            if (now < expiresAt) {
                                conn.rollback();
                                return false; 
                            }
                            
                            try (PreparedStatement del = conn.prepareStatement("DELETE FROM vatn_resource_locks WHERE resource_id = ?")) {
                                del.setString(1, resourceId);
                                del.executeUpdate();
                            }
                        }
                    }
                }

                String insSql = "INSERT INTO vatn_resource_locks (resource_id, owner_id, acquired_at, expires_at) VALUES (?, ?, ?, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(insSql)) {
                    pstmt.setString(1, resourceId);
                    pstmt.setString(2, ownerId);
                    pstmt.setLong(3, now);
                    pstmt.setLong(4, now + timeoutSeconds);
                    pstmt.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                return false;
            }
        } catch (SQLException e) {
            logger.error("[VATN-LOCK] DB connection failure: {}", e.getMessage());
            return false;
        }
    }

    private void renewLock(Connection conn, String resourceId, long timeoutSeconds) throws SQLException {
        long now = Instant.now().getEpochSecond();
        String updSql = "UPDATE vatn_resource_locks SET expires_at = ? WHERE resource_id = ? AND owner_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(updSql)) {
            pstmt.setLong(1, now + timeoutSeconds);
            pstmt.setString(2, resourceId);
            pstmt.setString(3, ownerId);
            pstmt.executeUpdate();
        }
    }

    @Override
    public void unlock(String resourceId) {
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("DELETE FROM vatn_resource_locks WHERE resource_id = ? AND owner_id = ?")) {
            pstmt.setString(1, resourceId);
            pstmt.setString(2, ownerId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("[VATN-LOCK] Failed to release lock: {}", e.getMessage());
        }
    }
}
