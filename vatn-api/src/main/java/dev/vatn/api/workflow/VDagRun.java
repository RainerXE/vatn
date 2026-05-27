package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.time.Instant;
import java.util.Map;

/**
 * Represents one execution instance of a {@link VDag}.
 *
 * @param runId           Unique run identifier (UUID).
 * @param dagId           The DAG this run belongs to.
 * @param state           Current lifecycle state.
 * @param logicalDate     The logical/data-interval date for this run (used in scheduled DAGs).
 * @param startDate       Wall-clock time when the first task started; null if not started.
 * @param endDate         Wall-clock time when the run completed; null if in progress.
 * @param externalTrigger True if triggered manually or via API (not by the scheduler).
 * @param conf            Run-level configuration overrides merged with {@link VDag#defaultArgs()}.
 */
@VatnApi(since = "1.0")
public record VDagRun(
    String runId,
    String dagId,
    VDagRunState state,
    Instant logicalDate,
    Instant startDate,
    Instant endDate,
    boolean externalTrigger,
    Map<String, String> conf
) {
    public VDagRun {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        if (dagId == null || dagId.isBlank()) throw new IllegalArgumentException("dagId must not be blank");
        if (state == null) state = VDagRunState.QUEUED;
        if (conf == null) conf = Map.of();
    }

    /** Returns a copy of this run with an updated state. */
    public VDagRun withState(VDagRunState newState) {
        Instant end = (newState == VDagRunState.SUCCESS || newState == VDagRunState.FAILED || newState == VDagRunState.CANCELED)
                ? Instant.now() : endDate;
        return new VDagRun(runId, dagId, newState, logicalDate, startDate, end, externalTrigger, conf);
    }
}
