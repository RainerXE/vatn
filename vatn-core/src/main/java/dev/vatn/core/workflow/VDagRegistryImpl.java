package dev.vatn.core.workflow;

import dev.vatn.api.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link VDagRegistry}.
 * DAG definitions and operators are registered at startup and held in memory.
 * Paused state is also in-memory (not persisted across restarts for alpha).
 */
public class VDagRegistryImpl implements VDagRegistry {
    private static final Logger logger = LoggerFactory.getLogger(VDagRegistryImpl.class);

    private final Map<String, VDag> dags = new ConcurrentHashMap<>();
    private final Map<String, VOperator> operators = new ConcurrentHashMap<>();
    private final Set<String> pausedDags = ConcurrentHashMap.newKeySet();

    public VDagRegistryImpl() {
        registerOperator(new VNoopOperator());
        registerOperator(new VProcessOperator());
    }

    @Override
    public void register(VDag dag) {
        dags.put(dag.id(), dag);
        logger.info("[DAG-REGISTRY] Registered DAG: {}", dag.id());
    }

    @Override
    public void registerOperator(VOperator operator) {
        operators.put(operator.operatorType(), operator);
        logger.debug("[DAG-REGISTRY] Registered operator: {}", operator.operatorType());
    }

    @Override
    public Optional<VDag> getDag(String dagId) {
        return Optional.ofNullable(dags.get(dagId));
    }

    @Override
    public List<VDag> listDags() {
        return List.copyOf(dags.values());
    }

    @Override
    public Optional<VOperator> getOperator(String operatorType) {
        return Optional.ofNullable(operators.get(operatorType));
    }

    @Override
    public void pause(String dagId) {
        pausedDags.add(dagId);
        logger.info("[DAG-REGISTRY] Paused DAG: {}", dagId);
    }

    @Override
    public void unpause(String dagId) {
        pausedDags.remove(dagId);
        logger.info("[DAG-REGISTRY] Unpaused DAG: {}", dagId);
    }

    @Override
    public boolean isPaused(String dagId) {
        return pausedDags.contains(dagId);
    }
}
