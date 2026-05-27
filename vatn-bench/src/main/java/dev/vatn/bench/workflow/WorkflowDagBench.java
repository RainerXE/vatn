package dev.vatn.bench.workflow;

import dev.vatn.api.workflow.*;
import dev.vatn.bench.VNodeState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * JMH benchmarks for VATN's VDagEngine.
 *
 * Measures wall-clock milliseconds from trigger() call to run SUCCESS, covering:
 *   - trigger_latency_single:  baseline — how fast one noop task completes end-to-end
 *   - serial_10_tasks:         10 noop tasks in a strict sequential chain
 *   - fanout_10_tasks:         1 root task fanning out to 10 parallel leaves
 *   - xcom_pipeline_5_tasks:   5 tasks passing XCom return values through a pipeline
 *
 * Competitor context (40 lightweight tasks, from public benchmarks):
 *   Windmill: ~2.4 s  |  Prefect: ~4.9 s  |  Apache Airflow: ~56 s
 *
 * Run: java -jar target/vatn-benchmarks.jar ".*WorkflowDagBench.*" -wi 3 -i 5 -f 1
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class WorkflowDagBench extends VNodeState {

    private VDagEngine   dagEngine;
    private VDagRegistry dagRegistry;
    private VEventLog    eventLog;

    @Setup(Level.Trial)
    @Override
    public void startNode() throws Exception {
        super.startNode();
        dagEngine   = context.getService(VDagEngine.class).orElseThrow(
                () -> new IllegalStateException("VDagEngine not registered"));
        dagRegistry = context.getService(VDagRegistry.class).orElseThrow(
                () -> new IllegalStateException("VDagRegistry not registered"));
        eventLog    = context.getService(VEventLog.class).orElse(null);
        registerBenchDags();
    }

    // ─── DAG definitions ──────────────────────────────────────────────────────

    private void registerBenchDags() {
        // 1. Single noop — pure trigger + dispatch latency
        dagRegistry.register(VDag.manual("bench-single",
                "Bench: single noop task",
                Map.of("t0", VDagTask.of("t0", "noop", Set.of(), Map.of()))));

        // 2. 10-task serial chain: t0 → t1 → … → t9
        Map<String, VDagTask> serial = new LinkedHashMap<>();
        serial.put("s0", VDagTask.of("s0", "noop", Set.of(), Map.of()));
        for (int i = 1; i < 10; i++) {
            serial.put("s" + i, VDagTask.of("s" + i, "noop", Set.of("s" + (i - 1)), Map.of()));
        }
        dagRegistry.register(VDag.manual("bench-serial-10", "Bench: 10-task serial chain", serial));

        // 3. Fan-out: 1 root → 10 parallel leaves (comparable to Windmill's parallel batch)
        Map<String, VDagTask> fanout = new LinkedHashMap<>();
        fanout.put("root", VDagTask.of("root", "noop", Set.of(), Map.of()));
        for (int i = 0; i < 10; i++) {
            fanout.put("leaf" + i, VDagTask.of("leaf" + i, "noop", Set.of("root"), Map.of()));
        }
        dagRegistry.register(VDag.manual("bench-fanout-10", "Bench: 10-task fan-out", fanout));

        // 4. XCom pipeline: 5 tasks, each writes a return value for the next to read
        dagRegistry.registerOperator(new XComWriteOperator());
        Map<String, VDagTask> xcom = new LinkedHashMap<>();
        xcom.put("x0", VDagTask.of("x0", "xcom-write", Set.of(), Map.of()));
        for (int i = 1; i < 5; i++) {
            xcom.put("x" + i, VDagTask.of("x" + i, "xcom-write", Set.of("x" + (i - 1)), Map.of()));
        }
        dagRegistry.register(VDag.manual("bench-xcom-5", "Bench: 5-task XCom pipeline", xcom));
    }

    /** Operator that reads the previous XCom value (if any) and writes its own. */
    static final class XComWriteOperator implements VOperator {
        @Override public String operatorType() { return "xcom-write"; }
        @Override public String execute(VTaskContext ctx) {
            String value = ctx.getTaskId() + ":result";
            ctx.getXCom().pushReturn(ctx.getTaskId(), value);
            return value;
        }
    }

    // ─── polling helper ───────────────────────────────────────────────────────

    private void awaitCompletion(String runId) {
        while (true) {
            Optional<VDagRun> run = dagEngine.getRunById(runId);
            if (run.isPresent()) {
                VDagRunState s = run.get().state();
                if (s == VDagRunState.SUCCESS || s == VDagRunState.FAILED || s == VDagRunState.CANCELED) {
                    return;
                }
            }
            // Park briefly — virtual-thread-friendly: yields carrier without blocking platform thread.
            // 200 µs is enough to let the engine's worker threads make progress.
            LockSupport.parkNanos(200_000L);
        }
    }

    // ─── benchmarks ───────────────────────────────────────────────────────────

    /**
     * Baseline: time from trigger to SUCCESS for a single noop task.
     * This is the minimum achievable latency — engine overhead only.
     */
    @Benchmark
    public void trigger_latency_single(Blackhole bh) {
        VDagRun run = dagEngine.trigger("bench-single");
        awaitCompletion(run.runId());
        bh.consume(run.runId());
    }

    /**
     * 10 tasks in a strict serial chain.
     * Measures total latency of sequential task dispatch × 10.
     */
    @Benchmark
    public void serial_10_tasks(Blackhole bh) {
        VDagRun run = dagEngine.trigger("bench-serial-10");
        awaitCompletion(run.runId());
        bh.consume(run.runId());
    }

    /**
     * 1 root task fanning out to 10 parallel leaves.
     * Measures how fast the engine saturates the default_pool with concurrent tasks.
     * Analogous to the Windmill/Airflow "40 lightweight parallel tasks" benchmark.
     */
    @Benchmark
    public void fanout_10_tasks(Blackhole bh) {
        VDagRun run = dagEngine.trigger("bench-fanout-10");
        awaitCompletion(run.runId());
        bh.consume(run.runId());
    }

    /**
     * 5-task pipeline where each task writes an XCom return value.
     * Measures the combined cost of SQLite XCom persistence + sequential dispatch.
     */
    @Benchmark
    public void xcom_pipeline_5_tasks(Blackhole bh) {
        VDagRun run = dagEngine.trigger("bench-xcom-5");
        awaitCompletion(run.runId());
        bh.consume(run.runId());
    }

    /**
     * fanOut API: trigger 10 single-task runs in parallel and wait for all.
     * Measures batch throughput. Comparable to Trigger.dev batchTriggerAndWait.
     */
    @Benchmark
    public void fanout_api_10_runs(Blackhole bh) {
        List<Map<String, String>> confs = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) confs.add(Map.of("idx", String.valueOf(i)));
        List<VDagRun> runs = dagEngine.fanOut("bench-single", confs);
        bh.consume(runs.size());
    }

    /**
     * Overhead of resumeInterruptedRuns() when no runs need recovery.
     * This is the steady-state cost paid on every node restart — the VEventLog
     * scan query against an otherwise empty interrupted-runs result set.
     */
    @Benchmark
    public void resume_interrupted_runs_idle(Blackhole bh) {
        dagEngine.resumeInterruptedRuns();
        bh.consume(1);
    }

    /**
     * Event log: raw append throughput — measures the cost of writing one
     * TASK_SUCCESS entry to the append-only SQLite log.
     * Provides a lower bound for per-task event log overhead at steady state.
     */
    @Benchmark
    public void event_log_append_single(Blackhole bh) {
        if (eventLog == null) { bh.consume(0); return; }
        String runId = "bench-" + System.nanoTime();
        eventLog.append(runId, "bench-single", "t0", "TASK_SUCCESS", "bench");
        bh.consume(runId);
    }
}
