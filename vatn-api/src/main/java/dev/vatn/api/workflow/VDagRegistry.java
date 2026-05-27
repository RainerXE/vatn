package dev.vatn.api.workflow;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.util.List;
import java.util.Optional;

/**
 * Registry for DAG definitions and their associated operators.
 *
 * <p>Applications call {@link #register} at startup; the {@link VDagEngine}
 * and {@link VDagScheduler} use this registry at runtime.
 */
@VatnApi(since = "1.0")
public interface VDagRegistry extends VService {

    /**
     * Registers a DAG. Replaces any previously registered DAG with the same ID.
     *
     * @param dag the DAG definition to register
     */
    void register(VDag dag);

    /**
     * Registers an operator implementation by its {@link VOperator#operatorType()} key.
     *
     * @param operator the operator instance (must be thread-safe)
     */
    void registerOperator(VOperator operator);

    /**
     * Looks up a registered DAG by ID.
     *
     * @param dagId the DAG identifier
     * @return the DAG definition, or empty if not registered
     */
    Optional<VDag> getDag(String dagId);

    /**
     * Returns all registered DAGs.
     */
    List<VDag> listDags();

    /**
     * Looks up a registered operator by type key.
     *
     * @param operatorType the operator type key
     * @return the operator, or empty if not registered
     */
    Optional<VOperator> getOperator(String operatorType);

    /**
     * Pauses a DAG — the scheduler will no longer trigger new runs,
     * but in-flight runs continue.
     *
     * @param dagId the DAG identifier
     */
    void pause(String dagId);

    /**
     * Unpauses a previously paused DAG.
     *
     * @param dagId the DAG identifier
     */
    void unpause(String dagId);

    /**
     * Returns true if the given DAG is paused.
     *
     * @param dagId the DAG identifier
     */
    boolean isPaused(String dagId);
}
