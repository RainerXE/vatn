package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.util.Map;
import java.util.Set;

/**
 * Defines a single node in a {@link VDag}.
 *
 * <p>Tasks form a directed acyclic graph via {@code upstream} references.
 * The operator identified by {@code operatorType} is resolved at runtime via
 * {@link VDagRegistry}.
 *
 * @param id               Unique task identifier within the DAG.
 * @param operatorType     Registered operator type key (e.g., {@code "noop"}, {@code "process"}).
 * @param upstream         Set of task IDs that must complete before this task may run.
 * @param triggerRule      Condition under which this task becomes eligible given upstream states.
 * @param retryPolicy      Retry configuration; defaults to {@link VRetryPolicy#NONE} if null.
 * @param pool             Pool name for concurrency limiting; null = {@link VPool#DEFAULT_POOL}.
 * @param slaSeconds       Maximum allowed wall-clock seconds for this task; 0 = unlimited.
 * @param maxActiveTis     Maximum concurrent instances of this task across all DAG runs; 0 = unlimited.
 * @param isSensor         If true, the operator is a polling sensor and may yield ({@link VTaskState#DEFERRED}).
 * @param pollIntervalMs   Polling interval in ms when {@code isSensor} is true.
 * @param group            Optional logical group label for UI grouping (like Airflow's TaskGroup).
 * @param metadata         Arbitrary key-value pairs for operator-specific configuration.
 */
@VatnApi(since = "1.0")
public record VDagTask(
    String id,
    String operatorType,
    Set<String> upstream,
    VTriggerRule triggerRule,
    VRetryPolicy retryPolicy,
    String pool,
    long slaSeconds,
    int maxActiveTis,
    boolean isSensor,
    long pollIntervalMs,
    String group,
    Map<String, Object> metadata
) {
    public VDagTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("VDagTask id must not be blank");
        if (operatorType == null || operatorType.isBlank()) throw new IllegalArgumentException("VDagTask operatorType must not be blank");
        if (upstream == null) upstream = Set.of();
        if (triggerRule == null) triggerRule = VTriggerRule.ALL_SUCCESS;
        if (retryPolicy == null) retryPolicy = VRetryPolicy.NONE;
        if (pool == null) pool = VPool.DEFAULT_POOL;
        if (metadata == null) metadata = Map.of();
    }

    /** Convenience builder for the common case of a non-sensor, non-grouped task. */
    public static VDagTask of(String id, String operatorType, Set<String> upstream, Map<String, Object> metadata) {
        return new VDagTask(id, operatorType, upstream, VTriggerRule.ALL_SUCCESS,
                VRetryPolicy.NONE, VPool.DEFAULT_POOL, 0, 0, false, 0, null, metadata);
    }
}
