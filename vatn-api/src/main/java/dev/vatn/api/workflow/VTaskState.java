package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Lifecycle state of a single task instance execution.
 * Mirrors Apache Airflow's TaskInstanceState for conceptual alignment.
 */
@VatnApi(since = "1.0")
public enum VTaskState {
    /** No run has been attempted. Initial state. */
    NONE,
    /** Waiting for the scheduler to pick it up. */
    SCHEDULED,
    /** In the execution queue, not yet dequeued. */
    QUEUED,
    /** Currently executing. */
    RUNNING,
    /** Completed successfully. */
    SUCCESS,
    /** Execution failed; no retries remaining. */
    FAILED,
    /** At least one upstream dependency failed, this task was skipped. */
    UPSTREAM_FAILED,
    /** Skipped by trigger rule (e.g., one_success met by another branch). */
    SKIPPED,
    /** Failed but retries remain; will re-enter SCHEDULED after delay. */
    UP_FOR_RETRY,
    /** A sensor that yielded — will re-poll after pollInterval. */
    DEFERRED,
    /** Removed from the DAG definition before completion. */
    REMOVED
}
