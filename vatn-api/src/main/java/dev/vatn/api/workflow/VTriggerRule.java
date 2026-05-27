package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Controls when a task becomes eligible to run based on the state of its upstream tasks.
 * Mirrors Apache Airflow's TriggerRule enum.
 */
@VatnApi(since = "1.0")
public enum VTriggerRule {
    /** Run only when all direct upstream tasks succeeded (default). */
    ALL_SUCCESS,
    /** Run only when all direct upstream tasks failed. */
    ALL_FAILED,
    /** Run when all direct upstream tasks completed (success or failure). */
    ALL_DONE,
    /** Run as soon as at least one upstream task succeeds. */
    ONE_SUCCESS,
    /** Run as soon as at least one upstream task fails. */
    ONE_FAILED,
    /** Run when no direct upstream task has failed. Skipped tasks are ignored. */
    NONE_FAILED,
    /** Run when no upstream task failed and at least one succeeded. */
    NONE_FAILED_OR_SKIPPED,
    /** Run unconditionally, regardless of upstream state. */
    ALWAYS
}
