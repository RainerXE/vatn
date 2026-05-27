package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.time.Instant;

/**
 * Represents one execution of a {@link VDagTask} within a {@link VDagRun}.
 *
 * @param taskId     ID of the task definition within the DAG.
 * @param runId      The DAG run this instance belongs to.
 * @param dagId      The DAG this instance belongs to.
 * @param state      Current lifecycle state.
 * @param tryNumber  1-based attempt counter (1 = first try, 2 = first retry, …).
 * @param startDate  Wall-clock time when execution began; null if not started.
 * @param endDate    Wall-clock time when execution finished; null if in progress.
 * @param hostname   Node ID that ran this task; null if not yet assigned.
 */
@VatnApi(since = "1.0")
public record VTaskInstance(
    String taskId,
    String runId,
    String dagId,
    VTaskState state,
    int tryNumber,
    Instant startDate,
    Instant endDate,
    String hostname
) {
    public VTaskInstance {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId must not be blank");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        if (dagId == null || dagId.isBlank()) throw new IllegalArgumentException("dagId must not be blank");
        if (state == null) state = VTaskState.NONE;
        if (tryNumber < 1) tryNumber = 1;
    }

    /** Returns a copy with an updated state and optionally updated timing. */
    public VTaskInstance withState(VTaskState newState) {
        Instant now = Instant.now();
        Instant start = (startDate == null && newState == VTaskState.RUNNING) ? now : startDate;
        Instant end = isTerminal(newState) ? now : endDate;
        return new VTaskInstance(taskId, runId, dagId, newState, tryNumber, start, end, hostname);
    }

    private static boolean isTerminal(VTaskState s) {
        return s == VTaskState.SUCCESS || s == VTaskState.FAILED
                || s == VTaskState.SKIPPED || s == VTaskState.UPSTREAM_FAILED
                || s == VTaskState.REMOVED;
    }
}
