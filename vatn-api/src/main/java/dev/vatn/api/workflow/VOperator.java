package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Executes the logic of a single {@link VDagTask}.
 *
 * <p>Implementations must be thread-safe; multiple task instances may call
 * {@link #execute} concurrently.
 *
 * <p>For sensor operators ({@link VDagTask#isSensor()} = true) the operator
 * should return {@code null} to indicate the condition is not yet met (triggering
 * re-polling after {@link VDagTask#pollIntervalMs()}), or a non-null value when
 * the condition is satisfied.
 *
 * <p>Applications register operators in {@link VDagRegistry} by type key.
 */
@VatnApi(since = "1.0")
public interface VOperator {

    /**
     * The unique type key that DAG task definitions reference via
     * {@link VDagTask#operatorType()}.
     */
    String operatorType();

    /**
     * Execute this task.
     *
     * @param ctx runtime context providing run identity, conf, XCom, and logging
     * @return the task's output string, stored in XCom under
     *         {@link VXCom#RETURN_VALUE_KEY}; may be null for sensor yield
     * @throws Exception on failure — the engine will apply retry policy
     */
    String execute(VTaskContext ctx) throws Exception;
}
