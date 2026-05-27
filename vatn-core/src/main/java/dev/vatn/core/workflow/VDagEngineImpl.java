package dev.vatn.core.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Core DAG execution engine.
 *
 * <p>Execution model:
 * <ol>
 *   <li>{@link #trigger} creates a {@link VDagRun} and {@link VTaskInstance} rows, then
 *       spawns a virtual thread to run the DAG.</li>
 *   <li>The run loop polls every {@link #POLL_INTERVAL_MS} ms for tasks whose upstream
 *       dependencies are satisfied and whose trigger rule passes.</li>
 *   <li>Each eligible task is dispatched to a virtual thread; pool slot acquisition
 *       happens inside that thread (may block briefly).</li>
 *   <li>On task completion/failure the state is persisted and the run loop re-evaluates.</li>
 *   <li>Retry: if a task fails and retries remain, it is re-queued after the computed
 *       back-off delay.</li>
 *   <li>Sensors: an operator returning {@code null} causes the task to enter
 *       {@link VTaskState#DEFERRED} and be re-polled after {@link VDagTask#pollIntervalMs()}.</li>
 * </ol>
 */
public class VDagEngineImpl implements VDagEngine {
    private static final Logger logger = LoggerFactory.getLogger(VDagEngineImpl.class);
    private static final long POLL_INTERVAL_MS = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VNodeContext nodeContext;
    private final VPersistenceService db;
    private final VDagRegistryImpl registry;
    private final VPoolManagerImpl poolManager;
    private final VSubscriptionImpl subscriptions;
    private final VEventLog eventLog;

    /** Active run IDs → set of in-flight task futures, for cancellation. */
    private final ConcurrentHashMap<String, Set<Future<?>>> activeRunTasks = new ConcurrentHashMap<>();
    /** Sensor re-poll timestamps: (runId + ":" + taskId) → next poll at */
    private final ConcurrentHashMap<String, Long> sensorNextPoll = new ConcurrentHashMap<>();

    public VDagEngineImpl(VNodeContext nodeContext, VDagRegistryImpl registry,
                          VPoolManagerImpl poolManager, VSubscriptionImpl subscriptions) {
        this.nodeContext = nodeContext;
        this.db = nodeContext.getService(VPersistenceService.class)
                .orElseThrow(() -> new IllegalStateException("VPersistenceService required"));
        this.registry = registry;
        this.poolManager = poolManager;
        this.subscriptions = subscriptions;
        this.eventLog = nodeContext.getService(VEventLog.class).orElse(null);
    }

    /**
     * Resumes any DAG runs that were RUNNING when the JVM last shut down.
     * Call this after all DAGs have been registered — typically during application startup.
     */
    public void resumeInterruptedRuns() {
        List<VDagRun> interrupted = listActiveRuns().stream()
                .filter(r -> r.state() == VDagRunState.RUNNING || r.state() == VDagRunState.QUEUED)
                .toList();
        if (interrupted.isEmpty()) return;
        logger.info("[DAG-ENGINE] Found {} interrupted run(s) to resume", interrupted.size());
        for (VDagRun run : interrupted) {
            VDag dag = registry.getDag(run.dagId()).orElse(null);
            if (dag == null) {
                logger.warn("[DAG-ENGINE] Cannot resume run {} — DAG '{}' not registered", run.runId(), run.dagId());
                continue;
            }
            logger.info("[DAG-ENGINE] Resuming interrupted run {} for DAG '{}'", run.runId(), run.dagId());
            if (eventLog != null) eventLog.append(run.runId(), run.dagId(), null, "DAG_RESUMED", null);
            Thread.ofVirtual().name("vatn-dag-resume-" + run.runId().substring(0, 8))
                    .start(() -> executeRun(dag, run, run.conf()));
        }
    }

    // ─────────────────────────────────────────────────────────────── trigger ──

    @Override
    public VDagRun trigger(String dagId, Map<String, String> conf, boolean externalTrigger) {
        VDag dag = registry.getDag(dagId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown DAG: " + dagId));

        checkNoCycles(dag);

        // Enforce maxActiveRuns
        if (dag.maxActiveRuns() > 0) {
            long active = getRuns(dagId, Integer.MAX_VALUE).stream()
                    .filter(r -> r.state() == VDagRunState.RUNNING || r.state() == VDagRunState.QUEUED)
                    .count();
            if (active >= dag.maxActiveRuns()) {
                throw new IllegalStateException("DAG " + dagId + " has reached maxActiveRuns=" + dag.maxActiveRuns());
            }
        }

        String runId = UUID.randomUUID().toString();
        Map<String, String> mergedConf = new HashMap<>(dag.defaultArgs());
        mergedConf.putAll(conf);

        VDagRun run = new VDagRun(runId, dagId, VDagRunState.QUEUED,
                Instant.now(), null, null, externalTrigger, Map.copyOf(mergedConf));
        persistRun(run);

        for (VDagTask task : dag.tasks().values()) {
            persistTaskInstance(new VTaskInstance(task.id(), runId, dagId,
                    VTaskState.SCHEDULED, 1, null, null, nodeContext.getNodeId()));
        }

        if (eventLog != null) eventLog.append(runId, dagId, null, "DAG_TRIGGERED", null);
        logger.info("[DAG-ENGINE] Triggered run {} for DAG {}", runId, dagId);
        Thread.ofVirtual().name("vatn-dag-run-" + runId.substring(0, 8))
                .start(() -> executeRun(dag, run, Map.copyOf(mergedConf)));
        return run;
    }

    private void checkNoCycles(VDag dag) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String taskId : dag.tasks().keySet()) {
            if (dfsCycleDetect(taskId, dag, visited, inStack)) {
                throw new IllegalArgumentException("DAG " + dag.id() + " contains a cycle involving task: " + taskId);
            }
        }
    }

    private boolean dfsCycleDetect(String taskId, VDag dag, Set<String> visited, Set<String> inStack) {
        if (inStack.contains(taskId)) return true;
        if (visited.contains(taskId)) return false;
        visited.add(taskId);
        inStack.add(taskId);
        VDagTask task = dag.tasks().get(taskId);
        if (task != null) {
            for (String upstream : task.upstream()) {
                if (dfsCycleDetect(upstream, dag, visited, inStack)) return true;
            }
        }
        inStack.remove(taskId);
        return false;
    }

    // ─────────────────────────────────────────────────────────── run loop ──

    private void executeRun(VDag dag, VDagRun initialRun, Map<String, String> conf) {
        String runId = initialRun.runId();
        activeRunTasks.put(runId, ConcurrentHashMap.newKeySet());

        try {
            updateRunState(runId, VDagRunState.RUNNING, Instant.now(), null);
            subscriptions.notifyRunChange(runId, dag.id(), VDagRunState.RUNNING);

            VXCom xcom = new VXComImpl(runId, db);
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

            while (true) {
                if (Thread.currentThread().isInterrupted()) break;

                Map<String, VTaskInstance> instances = getTaskInstanceMap(runId);

                // Check if SLA exceeded for the whole run
                if (dag.slaSeconds() > 0) {
                    Instant startDate = instances.values().stream()
                            .filter(ti -> ti.startDate() != null)
                            .map(VTaskInstance::startDate)
                            .min(Instant::compareTo).orElse(null);
                    if (startDate != null && Instant.now().isAfter(startDate.plusSeconds(dag.slaSeconds()))) {
                        logger.warn("[DAG-ENGINE] SLA exceeded for run {}", runId);
                        cancelAllTasks(runId);
                        finalizeRun(runId, dag, instances, VDagRunState.FAILED);
                        return;
                    }
                }

                if (allTerminal(instances)) break;

                for (VDagTask task : dag.tasks().values()) {
                    VTaskInstance ti = instances.get(task.id());
                    if (ti == null) continue;
                    if (!isReadyToStart(ti, task)) continue;

                    // Sensors: check if the re-poll time has arrived
                    if (ti.state() == VTaskState.DEFERRED) {
                        Long nextPoll = sensorNextPoll.get(runId + ":" + task.id());
                        if (nextPoll != null && System.currentTimeMillis() < nextPoll) continue;
                    }

                    if (!triggerRulePass(task, dag, instances)) {
                        // Upstream FAILED + no trigger rule to handle it → UPSTREAM_FAILED
                        if (shouldSkipDueToUpstream(task, dag, instances)) {
                            persistTaskInstance(ti.withState(VTaskState.UPSTREAM_FAILED));
                        }
                        continue;
                    }

                    // Mark as running in DB immediately so the loop doesn't re-dispatch it
                    persistTaskInstance(ti.withState(VTaskState.RUNNING));

                    Future<?> future = pool.submit(() -> executeTask(dag, task, runId, ti.tryNumber(), conf, xcom));
                    activeRunTasks.get(runId).add(future);
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);

            Map<String, VTaskInstance> finalInstances = getTaskInstanceMap(runId);
            VDagRunState finalState = computeFinalRunState(finalInstances);
            finalizeRun(runId, dag, finalInstances, finalState);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[DAG-ENGINE] Run {} interrupted", runId);
            updateRunState(runId, VDagRunState.CANCELED, null, Instant.now());
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] Run {} failed with unexpected error", runId, e);
            updateRunState(runId, VDagRunState.FAILED, null, Instant.now());
        } finally {
            activeRunTasks.remove(runId);
        }
    }

    // ──────────────────────────────────────────────────────────── task exec ──

    private void executeTask(VDag dag, VDagTask task, String runId, int tryNumber,
                             Map<String, String> conf, VXCom xcom) {
        // Replay skip: task already succeeded in this run before a crash
        if (eventLog != null && eventLog.hasSucceeded(runId, task.id())) {
            logger.info("[DAG-ENGINE] Task {}/{} already succeeded — skipping (replay)", dag.id(), task.id());
            eventLog.append(runId, dag.id(), task.id(), "TASK_SKIPPED_REPLAY", null);
            persistTaskInstance(new VTaskInstance(task.id(), runId, dag.id(),
                    VTaskState.SUCCESS, tryNumber, null, Instant.now(), nodeContext.getNodeId()));
            return;
        }

        int currentTry = tryNumber;
        while (true) {
            String poolName = task.pool();
            boolean slotAcquired = poolManager.acquireSlot(poolName);
            if (!slotAcquired) {
                logger.warn("[DAG-ENGINE] Pool slot acquire interrupted for task {}/{}", dag.id(), task.id());
                persistTaskInstance(new VTaskInstance(task.id(), runId, dag.id(),
                        VTaskState.FAILED, currentTry, Instant.now(), Instant.now(), nodeContext.getNodeId()));
                return;
            }

            boolean shouldRetry = false;
            long retryDelay = 0;

            try {
                VOperator operator = registry.getOperator(task.operatorType())
                        .orElseThrow(() -> new IllegalStateException("Unknown operator: " + task.operatorType()));

                VTaskContext ctx = new VTaskContextImpl(runId, dag.id(), task.id(), currentTry,
                        conf, task.metadata(), xcom, nodeContext);

                logger.info("[DAG-ENGINE] Executing task {}/{} (try {})", dag.id(), task.id(), currentTry);
                if (eventLog != null) eventLog.append(runId, dag.id(), task.id(), "TASK_STARTED", null);

                Instant deadline = task.slaSeconds() > 0 ? Instant.now().plusSeconds(task.slaSeconds()) : null;

                String output = null;
                Exception taskError = null;
                try {
                    output = operator.execute(ctx);
                    if (deadline != null && Instant.now().isAfter(deadline)) {
                        throw new RuntimeException("Task SLA exceeded (" + task.slaSeconds() + "s)");
                    }
                } catch (Exception e) {
                    taskError = e;
                }

                if (taskError == null && task.isSensor() && output == null) {
                    long nextPoll = System.currentTimeMillis() + Math.max(task.pollIntervalMs(), 1000);
                    sensorNextPoll.put(runId + ":" + task.id(), nextPoll);
                    persistTaskInstance(new VTaskInstance(task.id(), runId, dag.id(),
                            VTaskState.DEFERRED, currentTry, Instant.now(), null, nodeContext.getNodeId()));
                    logger.debug("[DAG-ENGINE] Sensor {} deferred, next poll in {}ms", task.id(), task.pollIntervalMs());
                    return;
                }

                if (taskError != null) {
                    VRetryPolicy policy = task.retryPolicy();
                    if (currentTry < policy.maxAttempts()) {
                        retryDelay = policy.delayForAttempt(currentTry);
                        logger.warn("[DAG-ENGINE] Task {}/{} failed (try {}), retrying in {}ms: {}",
                                dag.id(), task.id(), currentTry, retryDelay, taskError.getMessage());
                        persistTaskInstance(new VTaskInstance(task.id(), runId, dag.id(),
                                VTaskState.UP_FOR_RETRY, currentTry + 1, Instant.now(), null, nodeContext.getNodeId()));
                        shouldRetry = true;
                    } else {
                        logger.error("[DAG-ENGINE] Task {}/{} FAILED after {} tries", dag.id(), task.id(), currentTry, taskError);
                        if (eventLog != null)
                            eventLog.append(runId, dag.id(), task.id(), "TASK_FAILED", taskError.getMessage());
                        persistTaskInstance(new VTaskInstance(task.id(), runId, dag.id(),
                                VTaskState.FAILED, currentTry, Instant.now(), Instant.now(), nodeContext.getNodeId()));
                    }
                } else {
                    if (output != null) xcom.pushReturn(task.id(), output);
                    if (eventLog != null) eventLog.append(runId, dag.id(), task.id(), "TASK_SUCCESS", output);
                    persistTaskInstance(new VTaskInstance(task.id(), runId, dag.id(),
                            VTaskState.SUCCESS, currentTry, Instant.now(), Instant.now(), nodeContext.getNodeId()));
                    sensorNextPoll.remove(runId + ":" + task.id());
                    logger.info("[DAG-ENGINE] Task {}/{} SUCCESS", dag.id(), task.id());
                }

            } finally {
                poolManager.releaseSlot(poolName);
            }

            if (!shouldRetry) return;

            // Sleep between retries with slot released
            if (retryDelay > 0) {
                try { Thread.sleep(retryDelay); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    persistTaskInstance(new VTaskInstance(task.id(), runId, dag.id(),
                            VTaskState.FAILED, currentTry, Instant.now(), Instant.now(), nodeContext.getNodeId()));
                    return;
                }
            }
            currentTry++;
        }
    }

    // ────────────────────────────────────────────── trigger rule evaluation ──

    private boolean isReadyToStart(VTaskInstance ti, VDagTask task) {
        return ti.state() == VTaskState.SCHEDULED
                || (ti.state() == VTaskState.UP_FOR_RETRY)
                || (ti.state() == VTaskState.DEFERRED && task.isSensor());
    }

    private boolean triggerRulePass(VDagTask task, VDag dag, Map<String, VTaskInstance> instances) {
        if (task.upstream().isEmpty()) return true;
        // Use map lookup to avoid constructing a VTaskInstance with blank runId
        Set<VTaskState> upstreamStates = task.upstream().stream()
                .map(upId -> upstreamState(upId, instances))
                .collect(Collectors.toSet());

        return switch (task.triggerRule()) {
            case ALL_SUCCESS -> upstreamStates.stream().allMatch(s -> s == VTaskState.SUCCESS);
            case ALL_FAILED  -> upstreamStates.stream().allMatch(s -> s == VTaskState.FAILED);
            case ALL_DONE    -> upstreamStates.stream().allMatch(this::isTerminalState);
            case ONE_SUCCESS -> upstreamStates.contains(VTaskState.SUCCESS);
            case ONE_FAILED  -> upstreamStates.contains(VTaskState.FAILED);
            case NONE_FAILED -> upstreamStates.stream().noneMatch(s -> s == VTaskState.FAILED);
            case NONE_FAILED_OR_SKIPPED -> upstreamStates.stream().noneMatch(
                    s -> s == VTaskState.FAILED || s == VTaskState.SKIPPED);
            case ALWAYS      -> upstreamStates.stream().allMatch(this::isTerminalState);
        };
    }

    private boolean shouldSkipDueToUpstream(VDagTask task, VDag dag,
                                            Map<String, VTaskInstance> instances) {
        if (task.upstream().isEmpty()) return false;
        return task.upstream().stream().anyMatch(upId -> {
            VTaskState s = upstreamState(upId, instances);
            return s == VTaskState.FAILED || s == VTaskState.UPSTREAM_FAILED;
        }) && task.triggerRule() == VTriggerRule.ALL_SUCCESS;
    }

    private static VTaskState upstreamState(String upId, Map<String, VTaskInstance> instances) {
        VTaskInstance ti = instances.get(upId);
        return ti != null ? ti.state() : VTaskState.NONE;
    }

    // Placeholder to avoid null-checks — task ID mismatch is caught via logging
    private static VTaskInstance dummyInstance(String taskId, String dagId, String runId) {
        return new VTaskInstance(taskId, runId, dagId, VTaskState.NONE, 1, null, null, null);
    }

    private boolean isTerminalState(VTaskState s) {
        return s == VTaskState.SUCCESS || s == VTaskState.FAILED
                || s == VTaskState.SKIPPED || s == VTaskState.UPSTREAM_FAILED
                || s == VTaskState.REMOVED;
    }

    private boolean allTerminal(Map<String, VTaskInstance> instances) {
        return instances.values().stream().allMatch(ti -> isTerminalState(ti.state()));
    }

    private VDagRunState computeFinalRunState(Map<String, VTaskInstance> instances) {
        boolean anyFailed = instances.values().stream()
                .anyMatch(ti -> ti.state() == VTaskState.FAILED || ti.state() == VTaskState.UPSTREAM_FAILED);
        return anyFailed ? VDagRunState.FAILED : VDagRunState.SUCCESS;
    }

    // ────────────────────────────────────────────────── fan-out / query API ──

    @Override
    public List<VDagRun> fanOut(String dagId, List<Map<String, String>> confs) {
        List<CompletableFuture<VDagRun>> futures = confs.stream()
                .map(conf -> CompletableFuture.supplyAsync(
                        () -> awaitRun(trigger(dagId, conf, false)),
                        Executors.newVirtualThreadPerTaskExecutor()))
                .toList();
        return futures.stream().map(f -> {
            try { return f.get(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).toList();
    }

    private VDagRun awaitRun(VDagRun initial) {
        String runId = initial.runId();
        while (true) {
            Optional<VDagRun> run = getRunById(runId);
            if (run.isPresent()) {
                VDagRunState s = run.get().state();
                if (s == VDagRunState.SUCCESS || s == VDagRunState.FAILED || s == VDagRunState.CANCELED) {
                    return run.get();
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return initial;
            }
        }
    }

    @Override
    public void cancel(String runId) {
        Set<Future<?>> tasks = activeRunTasks.get(runId);
        if (tasks != null) tasks.forEach(f -> f.cancel(true));
        cancelAllTasks(runId);
        updateRunState(runId, VDagRunState.CANCELED, null, Instant.now());
        logger.info("[DAG-ENGINE] Canceled run {}", runId);
    }

    private void cancelAllTasks(String runId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_task_instance SET state='FAILED', end_date=datetime('now')
                WHERE run_id=? AND state NOT IN ('SUCCESS','FAILED','SKIPPED','UPSTREAM_FAILED','REMOVED')
                """)) {
            ps.setString(1, runId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] Failed to cancel tasks for run {}", runId, e);
        }
    }

    @Override
    public Optional<VDagRun> getRunById(String runId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM vatn_dag_run WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rowToRun(rs));
            }
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] getRunById failed: {}", runId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<VDagRun> getRuns(String dagId, int limit) {
        List<VDagRun> runs = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM vatn_dag_run WHERE dag_id=? ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, dagId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) runs.add(rowToRun(rs));
            }
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] getRuns failed for dagId={}", dagId, e);
        }
        return runs;
    }

    @Override
    public List<VTaskInstance> getTaskInstances(String runId) {
        List<VTaskInstance> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM vatn_task_instance WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rowToTaskInstance(rs));
            }
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] getTaskInstances failed: {}", runId, e);
        }
        return list;
    }

    @Override
    public Optional<VTaskInstance> getTaskInstance(String runId, String taskId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM vatn_task_instance WHERE run_id=? AND task_id=?")) {
            ps.setString(1, runId);
            ps.setString(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rowToTaskInstance(rs));
            }
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] getTaskInstance failed: {}/{}", runId, taskId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<VDagRun> listActiveRuns() {
        List<VDagRun> runs = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM vatn_dag_run WHERE state IN ('QUEUED','RUNNING') ORDER BY created_at DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) runs.add(rowToRun(rs));
            }
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] listActiveRuns failed", e);
        }
        return runs;
    }

    @Override
    public List<VDagRun> listRunsByTag(String tag, int limit) {
        // Tags are stored on VDag (not VDagRun), so we join in-memory via registry
        Set<String> dagIds = registry.listDags().stream()
                .filter(d -> d.tags().contains(tag))
                .map(VDag::id)
                .collect(Collectors.toSet());
        if (dagIds.isEmpty()) return List.of();
        List<VDagRun> result = new ArrayList<>();
        for (String dagId : dagIds) result.addAll(getRuns(dagId, limit));
        result.sort((a, b) -> b.logicalDate().compareTo(a.logicalDate()));
        return result.stream().limit(limit).toList();
    }

    // ─────────────────────────────────────────────────────── persistence ──

    private void persistRun(VDagRun run) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO vatn_dag_run(run_id, dag_id, state, logical_date,
                    start_date, end_date, external_trigger, conf)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, run.runId());
            ps.setString(2, run.dagId());
            ps.setString(3, run.state().name());
            ps.setString(4, run.logicalDate() != null ? run.logicalDate().toString() : null);
            ps.setString(5, run.startDate() != null ? run.startDate().toString() : null);
            ps.setString(6, run.endDate() != null ? run.endDate().toString() : null);
            ps.setInt(7, run.externalTrigger() ? 1 : 0);
            ps.setString(8, mapToJson(run.conf()));
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] persistRun failed: {}", run.runId(), e);
        }
    }

    private void updateRunState(String runId, VDagRunState state, Instant startDate, Instant endDate) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_dag_run SET state=?,
                    start_date=COALESCE(NULLIF(?, ''), start_date),
                    end_date=COALESCE(NULLIF(?, ''), end_date)
                WHERE run_id=?
                """)) {
            ps.setString(1, state.name());
            ps.setString(2, startDate != null ? startDate.toString() : "");
            ps.setString(3, endDate != null ? endDate.toString() : "");
            ps.setString(4, runId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] updateRunState failed: {} {}", runId, state, e);
        }
    }

    private void finalizeRunIfRunning(String runId, VDagRunState state) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                UPDATE vatn_dag_run SET state=?, end_date=?
                WHERE run_id=? AND state='RUNNING'
                """)) {
            ps.setString(1, state.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, runId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] finalizeRunIfRunning failed: {} {}", runId, state, e);
        }
    }

    private void finalizeRun(String runId, VDag dag, Map<String, VTaskInstance> instances, VDagRunState state) {
        // Only transition from RUNNING — prevents overwriting an external CANCELED signal
        finalizeRunIfRunning(runId, state);
        if (eventLog != null) {
            eventLog.append(runId, dag.id(), null,
                state == VDagRunState.SUCCESS ? "DAG_SUCCESS" : "DAG_FAILED", null);
        }
        subscriptions.notifyRunChange(runId, dag.id(), state);
        logger.info("[DAG-ENGINE] Run {} finalized: {}", runId, state);
    }

    private void persistTaskInstance(VTaskInstance ti) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT OR REPLACE INTO vatn_task_instance(task_id, run_id, dag_id, state, try_number,
                    start_date, end_date, hostname)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, ti.taskId());
            ps.setString(2, ti.runId());
            ps.setString(3, ti.dagId());
            ps.setString(4, ti.state().name());
            ps.setInt(5, ti.tryNumber());
            ps.setString(6, ti.startDate() != null ? ti.startDate().toString() : null);
            ps.setString(7, ti.endDate() != null ? ti.endDate().toString() : null);
            ps.setString(8, ti.hostname());
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[DAG-ENGINE] persistTaskInstance failed: {}/{}", ti.runId(), ti.taskId(), e);
        }
    }

    private Map<String, VTaskInstance> getTaskInstanceMap(String runId) {
        Map<String, VTaskInstance> map = new HashMap<>();
        getTaskInstances(runId).forEach(ti -> map.put(ti.taskId(), ti));
        return map;
    }

    // ──────────────────────────────────────────────────────── row mapping ──

    private VDagRun rowToRun(ResultSet rs) throws Exception {
        return new VDagRun(
            rs.getString("run_id"),
            rs.getString("dag_id"),
            VDagRunState.valueOf(rs.getString("state")),
            parseInstant(rs.getString("logical_date")),
            parseInstant(rs.getString("start_date")),
            parseInstant(rs.getString("end_date")),
            rs.getInt("external_trigger") == 1,
            parseConf(rs.getString("conf"))
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseConf(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private VTaskInstance rowToTaskInstance(ResultSet rs) throws Exception {
        return new VTaskInstance(
            rs.getString("task_id"),
            rs.getString("run_id"),
            rs.getString("dag_id"),
            VTaskState.valueOf(rs.getString("state")),
            rs.getInt("try_number"),
            parseInstant(rs.getString("start_date")),
            parseInstant(rs.getString("end_date")),
            rs.getString("hostname")
        );
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    private String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey().replace("\"", "\\\"")).append("\":");
            sb.append("\"").append(entry.getValue() != null ? entry.getValue().replace("\"", "\\\"") : "").append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

}
