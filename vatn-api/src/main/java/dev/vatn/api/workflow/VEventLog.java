package dev.vatn.api.workflow;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.util.List;

/**
 * Append-only task-transition event log for crash-safe DAG execution.
 *
 * <p>Unlike {@code vatn_task_instance} which is mutable (INSERT OR REPLACE),
 * the event log is monotonically growing. This allows the DAG engine to:
 * <ul>
 *   <li>Detect runs interrupted by a JVM crash (DAG_TRIGGERED but no DAG_SUCCESS/DAG_FAILED).</li>
 *   <li>Skip tasks that already succeeded before the crash (TASK_SUCCESS entry present).</li>
 *   <li>Provide an immutable audit trail for debugging and observability.</li>
 * </ul>
 *
 * <p>Standard event types: {@code DAG_TRIGGERED}, {@code DAG_RESUMED},
 * {@code TASK_STARTED}, {@code TASK_SUCCESS}, {@code TASK_FAILED},
 * {@code TASK_SKIPPED_REPLAY}, {@code DAG_SUCCESS}, {@code DAG_FAILED}.
 */
@VatnApi(since = "1.1")
public interface VEventLog extends VService {

    /**
     * Appends an event to the log. All parameters except {@code payload} are required.
     * {@code taskId} may be null for run-level events (DAG_TRIGGERED, DAG_SUCCESS, etc.).
     */
    void append(String runId, String dagId, String taskId, String eventType, String payload);

    /**
     * Returns true if there is a {@code TASK_SUCCESS} entry for this run/task combination.
     * Used by the resume path to skip already-completed tasks.
     */
    boolean hasSucceeded(String runId, String taskId);

    /**
     * Returns run IDs that were triggered (have a {@code DAG_TRIGGERED} entry) but
     * never completed (no {@code DAG_SUCCESS} or {@code DAG_FAILED} entry).
     * These are candidates for crash recovery.
     */
    List<String> getInterruptedRunIds();
}
