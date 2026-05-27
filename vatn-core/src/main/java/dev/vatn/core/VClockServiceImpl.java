package dev.vatn.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.vatn.api.VClockService;
import dev.vatn.api.VNodeContext;

/**
 * Persistent Clock and Metronome Service.
 * Tracks exact datetimes in the database to survive restarts.
 */
public class VClockServiceImpl implements VClockService, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(VClockServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final VPersistenceService db;
    private final VNodeContext context;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public VClockServiceImpl(VNodeContext context, VPersistenceService db) {
        this.context = context;
        this.db = db;
        bootstrap();
    }

    private void bootstrap() {
        logger.info("Clock Service Bootstrapping: Recovering missed tasks...");
        
        try (Connection conn = db.getConnection()) {
            // 1. Recover missed tasks
            String sql = "SELECT * FROM vatn_scheduled_tasks WHERE target_time <= ? AND status = 'PENDING'";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, Instant.now().toEpochMilli());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    String taskId = rs.getString("id");
                    String command = rs.getString("command");
                    logger.warn("Recovering missed task {}: {}", taskId, command);
                    triggerTask(taskId, command);
                }
            }
            
            // 2. Start Poller
            scheduler.scheduleAtFixedRate(this::pollAndTrigger, 0, 1, TimeUnit.MINUTES);
            
        } catch (SQLException e) {
            logger.error("Failed to bootstrap Clock Service", e);
        }
    }

    private void pollAndTrigger() {
        try (Connection conn = db.getConnection()) {
            String sql = "SELECT * FROM vatn_scheduled_tasks WHERE target_time <= ? AND status = 'PENDING'";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, Instant.now().toEpochMilli());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    triggerTask(rs.getString("id"), rs.getString("command"));
                }
            }
        } catch (SQLException e) {
            logger.error("Clock Poller failed", e);
        }
    }

    private void triggerTask(String taskId, String command) {
        logger.info("Triggering Scheduled Task {}: {}", taskId, command);
        
        // Use Messaging Bus to notify the agent
        try {
            String body = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("taskId", taskId).put("command", command));
            context.getMessaging().publish("vatn.clock.trigger",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.error("Failed to serialize trigger payload for task {}", taskId, e);
            return;
        }

        // Update Status
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("UPDATE vatn_scheduled_tasks SET status = 'COMPLETED' WHERE id = ?")) {
            pstmt.setString(1, taskId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update task status for {}", taskId, e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    public void schedule(String taskId, String agentId, String command, Instant time) {
        logger.info("Scheduling task {} for agent {} at {}", taskId, agentId, time);
        
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO vatn_scheduled_tasks (id, agent_id, command, target_time, status) VALUES (?, ?, ?, ?, 'PENDING')")) {
            pstmt.setString(1, taskId);
            pstmt.setString(2, agentId);
            pstmt.setString(3, command);
            pstmt.setLong(4, time.toEpochMilli());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to schedule task {}", taskId, e);
        }
    }
}
