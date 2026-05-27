package dev.vatn.api;

import java.time.Instant;

/**
 * Interface for persistent scheduling and time-based triggers.
 * Allows agents to schedule tasks that survive node restarts.
 */
@VatnApi(since = "1.0")
public interface VClockService extends VService {
    
    /**
     * Schedules a task to be triggered at a specific time.
     * 
     * @param taskId Unique identifier for the task.
     * @param agentId ID of the agent owning the task.
     * @param command The command or query to be triggered.
     * @param time The target execution time.
     */
    void schedule(String taskId, String agentId, String command, Instant time);
}
