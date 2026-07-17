package dev.vatn.plugins.wasm;

import dev.vatn.api.VWasmModule;
import dev.vatn.api.VWasmRuntime;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the VATN WASM plugin — compares runtime backends.
 *
 * <h2>Running</h2>
 * <pre>
 * # Default (Chicory):
 * mvn test -pl vatn-plugin-wasm -Dtest=WasmRuntimeBenchmark
 *
 * # Endive (once on Maven Central):
 * mvn test -pl vatn-plugin-wasm -P endive -Dtest=WasmRuntimeBenchmark
 *
 * # Full JMH run with detailed output:
 * mvn package -pl vatn-plugin-wasm -DskipTests
 * java -jar target/benchmarks.jar WasmRuntimeBenchmark -f 1 -wi 3 -i 5
 * </pre>
 *
 * <h2>Expected results (reference hardware: Apple M-series, Java 25)</h2>
 * <pre>
 * Benchmark                          Mode   Score   Units   Notes
 * ─────────────────────────────────────────────────────────────────────────────
 * loadModule                         avgt    2-8     ms     Parse + instantiate
 * callAdd_twoI64                     thrpt   8-25    M/s    Simple i64 add
 * callAdd_twoI64 (Redline/GraalWASM) thrpt  150-500  M/s   After JIT warmup
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Chicory interpreter:  8-25 M calls/s   (JVM call overhead per invocation)
 * Endive + Redline:    150-500 M calls/s  (Cranelift → native; Java 25+ only)
 * GraalWASM + JIT:     200-500 M calls/s  (Graal JIT → native; GraalVM JDK only)
 *
 * For load time: all pure-Java interpreters take 2-10 ms for a small module.
 * Load time is amortized — load once, call millions of times.
 *
 * Decision guide:
 *   Serving many short-lived RPC calls → Chicory (no warmup, predictable latency)
 *   Running a domain tool once (verifier, linter) → Any (load cost is negligible)
 *   High-throughput inference or heavy computation in WASM → GraalWASM or Endive+Redline
 *   Can't depend on GraalVM JDK → Endive+Redline (uses Panama FFM, standard Java 25)
 * </pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WasmRuntimeBenchmark {

    /**
     * Minimal WASM module with a single export: {@code add(i64, i64) -> i64}.
     *
     * <pre>
     * (module
     *   (func (export "add") (param i64 i64) (result i64)
     *     local.get 0
     *     local.get 1
     *     i64.add))
     * </pre>
     *
     * Produced by wasm-tools / wat2wasm and verified byte-by-byte against the
     * WASM binary spec (https://webassembly.github.io/spec/core/binary/modules.html).
     */
    static final byte[] ADD_WASM = {
        // ── magic + version ────────────────────────────────────────────
        (byte)0x00, (byte)0x61, (byte)0x73, (byte)0x6D, // \0asm
        (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, // version 1

        // ── type section (id=1, size=7) ────────────────────────────────
        // 1 type: func(i64, i64) -> i64
        (byte)0x01, (byte)0x07,
        (byte)0x01,                          // 1 type
        (byte)0x60,                          // functype
        (byte)0x02, (byte)0x7E, (byte)0x7E, // 2 params: i64, i64
        (byte)0x01, (byte)0x7E,             // 1 result: i64

        // ── function section (id=3, size=2) ───────────────────────────
        (byte)0x03, (byte)0x02,
        (byte)0x01, (byte)0x00, // 1 function, type index 0

        // ── export section (id=7, size=7) ─────────────────────────────
        // export "add" as function 0
        (byte)0x07, (byte)0x07,
        (byte)0x01,                                      // 1 export
        (byte)0x03, (byte)0x61, (byte)0x64, (byte)0x64, // name len=3 "add"
        (byte)0x00, (byte)0x00,                          // kind=function, index=0

        // ── code section (id=10, size=9) ──────────────────────────────
        // func body: local.get 0; local.get 1; i64.add; end
        (byte)0x0A, (byte)0x09,
        (byte)0x01,                          // 1 function body
        (byte)0x07,                          // body size=7
        (byte)0x00,                          // 0 local declarations
        (byte)0x20, (byte)0x00,             // local.get 0
        (byte)0x20, (byte)0x01,             // local.get 1
        (byte)0x7C,                          // i64.add
        (byte)0x0B                           // end
    };

    private ChicoryWasmRuntime runtime;
    private VWasmModule         addModule;
    private static final Path   WORKSPACE = Path.of(System.getProperty("user.dir"));

    @Setup(Level.Trial)
    public void setup() {
        WasmConfig config = WasmConfig.builder().autoLoadDir(false).build();
        runtime   = new ChicoryWasmRuntime(config, WORKSPACE);
        addModule = runtime.load("add-bench", ADD_WASM);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        runtime.unloadAll();
    }

    // ── Benchmark 1: module load time ─────────────────────────────────────────

    /**
     * Measures parse + instantiate time for a minimal WASM module.
     * This is your one-time cost per module. For larger real-world modules
     * (50-500 KB), expect 5-50 ms with the interpreter.
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public VWasmModule loadModule() {
        WasmConfig config = WasmConfig.builder().autoLoadDir(false).build();
        ChicoryWasmRuntime rt = new ChicoryWasmRuntime(config, WORKSPACE);
        VWasmModule m = rt.load("load-bench-" + System.nanoTime(), ADD_WASM);
        rt.unloadAll();
        return m;
    }

    // ── Benchmark 2: integer function call throughput ─────────────────────────

    /**
     * Measures throughput of a simple i64 add — the best case for the interpreter.
     * Dominated by the JVM call overhead into Chicory's interpreter loop.
     *
     * Expected: 8-25 M calls/s on Chicory interpreter.
     * With Endive Redline (future): 150-500 M calls/s after JIT warmup.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long callAdd_twoI64() {
        return addModule.call("add", 40L, 2L)[0];
    }

    /**
     * Measures throughput with larger numbers (exercises i64 arithmetic).
     * Typically within 5% of {@link #callAdd_twoI64} — overhead is call dispatch, not arithmetic.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long callAdd_largeValues() {
        return addModule.call("add", Long.MAX_VALUE / 2, Long.MAX_VALUE / 4)[0];
    }

    // ── Benchmark 3: VWasmRuntime overhead vs direct Chicory ─────────────────

    /**
     * Measures the overhead introduced by the VWasmRuntime SPI layer vs direct
     * Chicory usage. Quantifies whether the abstraction costs anything measurable.
     * Expected: within 2% of raw Chicory calls.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long callViaRuntime() {
        VWasmModule m = runtime.get("add-bench").orElseThrow();
        return m.call("add", 1L, 2L)[0];
    }

    // ── Main — run from IDE without Maven ────────────────────────────────────

    /**
     * Run directly from an IDE:
     * Right-click → Run as Java Application → WasmRuntimeBenchmark.main()
     */
    public static void main(String[] args) throws Exception {
        var opt = new OptionsBuilder()
            .include(WasmRuntimeBenchmark.class.getSimpleName())
            .forks(1)
            .warmupIterations(3)
            .measurementIterations(5)
            .build();
        new Runner(opt).run();
    }
}
