package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

import java.util.Optional;

/**
 * Cross-task communication within a DAG run.
 *
 * <p>Operators use XCom to pass data downstream without coupling task definitions.
 * All values are scoped to a single {@link VDagRun} and keyed by (taskId, key).
 *
 * <p>Mirrors Apache Airflow's XCom concept. Values are stored as strings; callers
 * are responsible for any JSON serialization.
 */
@VatnApi(since = "1.0")
public interface VXCom {

    /** Default XCom key used when an operator returns a plain string result. */
    String RETURN_VALUE_KEY = "return_value";

    /**
     * Stores a value produced by the given task.
     *
     * @param taskId the task producing the value
     * @param key    the XCom key
     * @param value  the value to store (may be JSON-encoded for complex data)
     */
    void push(String taskId, String key, String value);

    /**
     * Retrieves a value stored by a task.
     *
     * @param taskId the task that produced the value
     * @param key    the XCom key
     * @return the stored value, or empty if not found
     */
    Optional<String> pull(String taskId, String key);

    /** Convenience: push the operator's return value under {@link #RETURN_VALUE_KEY}. */
    default void pushReturn(String taskId, String value) {
        push(taskId, RETURN_VALUE_KEY, value);
    }

    /** Convenience: pull the operator's return value. */
    default Optional<String> pullReturn(String taskId) {
        return pull(taskId, RETURN_VALUE_KEY);
    }
}
