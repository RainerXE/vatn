package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.util.Map;
import java.util.Set;

/**
 * Defines a Directed Acyclic Graph of tasks — the core workflow primitive.
 *
 * <p>A {@code VDag} is a blueprint. Each execution is a {@link VDagRun}.
 * The dependency graph is expressed implicitly via {@link VDagTask#upstream()} references.
 *
 * @param id             Unique DAG identifier (e.g., {@code "nightly-build"}).
 * @param description    Human-readable purpose of this DAG.
 * @param schedule       Cron expression for automatic triggering (e.g., {@code "0 2 * * *"});
 *                       null or blank means trigger-only (no automatic schedule).
 * @param maxActiveRuns  Maximum simultaneous DAG runs; 1 = serialized, 0 = unlimited.
 * @param catchUp        If true, the scheduler will backfill missed scheduled runs.
 * @param slaSeconds     Maximum allowed wall-clock seconds for the entire DAG run; 0 = unlimited.
 * @param tags           Arbitrary labels for filtering and grouping (like Airflow's DAG tags).
 * @param tasks          Map of task ID → {@link VDagTask} definition.
 * @param defaultArgs    Default key-value pairs merged into every DAG run's conf.
 */
@VatnApi(since = "1.0")
public record VDag(
    String id,
    String description,
    String schedule,
    int maxActiveRuns,
    boolean catchUp,
    long slaSeconds,
    Set<String> tags,
    Map<String, VDagTask> tasks,
    Map<String, String> defaultArgs
) {
    public VDag {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("VDag id must not be blank");
        if (tasks == null) tasks = Map.of();
        if (tags == null) tags = Set.of();
        if (defaultArgs == null) defaultArgs = Map.of();
        if (maxActiveRuns < 0) throw new IllegalArgumentException("maxActiveRuns must be >= 0");
    }

    /** Convenience factory for a manually-triggered DAG with no schedule. */
    public static VDag manual(String id, String description, Map<String, VDagTask> tasks) {
        return new VDag(id, description, null, 1, false, 0, Set.of(), tasks, Map.of());
    }

    /** Convenience factory for a cron-scheduled DAG. */
    public static VDag scheduled(String id, String description, String cron, Map<String, VDagTask> tasks) {
        return new VDag(id, description, cron, 1, false, 0, Set.of(), tasks, Map.of());
    }
}
