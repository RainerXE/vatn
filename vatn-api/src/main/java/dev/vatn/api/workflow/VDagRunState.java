package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Lifecycle state of a DAG run (one execution of a {@link VDag}).
 */
@VatnApi(since = "1.0")
public enum VDagRunState {
    /** Accepted but not yet started. */
    QUEUED,
    /** At least one task is running. */
    RUNNING,
    /** All tasks completed successfully. */
    SUCCESS,
    /** At least one task failed and no further progress is possible. */
    FAILED,
    /** Explicitly canceled by a user or system action. */
    CANCELED
}
