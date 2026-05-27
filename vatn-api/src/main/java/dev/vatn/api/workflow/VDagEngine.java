package dev.vatn.api.workflow;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SPI for executing DAG runs.
 *
 * <p>The engine manages the full lifecycle of {@link VDagRun} instances:
 * scheduling tasks in topological order, applying trigger rules, enforcing
 * concurrency pools, retrying failed tasks, and persisting state.
 *
 * <p>Obtain via {@code context.getService(VDagEngine.class)}.
 */
@VatnApi(since = "1.0")
public interface VDagEngine extends VService {

    /**
     * Triggers a new DAG run for the given DAG ID.
     *
     * @param dagId           the registered DAG to run
     * @param conf            run-level configuration; merged with {@link VDag#defaultArgs()}
     * @param externalTrigger true if triggered by a user/API call (not scheduler)
     * @return the newly created {@link VDagRun} in QUEUED state
     * @throws IllegalArgumentException if dagId is not registered
     */
    VDagRun trigger(String dagId, Map<String, String> conf, boolean externalTrigger);

    /** Convenience: trigger with empty conf and externalTrigger = true. */
    default VDagRun trigger(String dagId) {
        return trigger(dagId, Map.of(), true);
    }

    /**
     * Cancels a running or queued DAG run.
     *
     * @param runId the run ID to cancel
     */
    void cancel(String runId);

    /**
     * Retrieves a DAG run by ID.
     *
     * @param runId the run ID
     * @return the run, or empty if not found
     */
    Optional<VDagRun> getRunById(String runId);

    /**
     * Lists the most recent DAG runs for a given DAG.
     *
     * @param dagId the DAG ID
     * @param limit maximum number of runs to return (most recent first)
     * @return list of runs
     */
    List<VDagRun> getRuns(String dagId, int limit);

    /**
     * Lists all task instances for a specific DAG run.
     *
     * @param runId the DAG run ID
     * @return task instances, one per task in the DAG
     */
    List<VTaskInstance> getTaskInstances(String runId);

    /**
     * Retrieves a specific task instance.
     *
     * @param runId  the DAG run ID
     * @param taskId the task ID
     * @return the task instance, or empty if not found
     */
    Optional<VTaskInstance> getTaskInstance(String runId, String taskId);

    /**
     * Fan-out: triggers parallel child runs with distinct configurations and waits
     * for all to complete. Analogous to Trigger.dev's {@code batchTriggerAndWait}.
     *
     * @param dagId the DAG to fan out
     * @param confs one conf map per child run
     * @return list of completed (or failed) runs, one per conf entry, in order
     */
    List<VDagRun> fanOut(String dagId, List<Map<String, String>> confs);

    /**
     * Lists all currently active runs across all DAGs.
     */
    List<VDagRun> listActiveRuns();

    /**
     * Lists runs filtered by tag. Tags are matched against the corresponding {@link VDag#tags()}.
     *
     * @param tag   the tag to filter on
     * @param limit maximum number of runs to return
     */
    List<VDagRun> listRunsByTag(String tag, int limit);

    /**
     * Resumes any DAG runs that were RUNNING or QUEUED when the JVM last shut down.
     *
     * <p>Call this after all DAGs have been registered — typically at the end of
     * application startup. Runs whose DAG definition is no longer registered are skipped
     * with a warning.
     *
     * <p>This is VATN's crash-safe execution mechanism: completed tasks (those with a
     * {@link VEventLog} {@code TASK_SUCCESS} entry) are skipped; incomplete tasks
     * resume from where execution stopped.
     */
    default void resumeInterruptedRuns() {
        // default no-op; VDagEngineImpl overrides this
    }
}
