package dev.vatn.bench.local;

import dev.vatn.api.VProcessService;
import dev.vatn.bench.VNodeState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * VProcessService: process spawn latency, output capture throughput,
 * and async handle lifecycle. Uses trivial shell commands (echo, true)
 * so numbers reflect VATN overhead, not workload time.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ProcessBench extends VNodeState {

    private VProcessService proc;
    private String workdir;

    @Setup(Level.Trial)
    @Override
    public void startNode() throws Exception {
        super.startNode();
        proc = context.getService(VProcessService.class).orElseThrow();
        workdir = System.getProperty("user.dir");
    }

    @Benchmark
    public String execute_echo(Blackhole bh) throws IOException {
        VProcessService.VProcessResult r = proc.execute(
            List.of("echo", "vatn-bench"), Map.of(), workdir);
        return r.stdout().trim();
    }

    @Benchmark
    public int execute_true(Blackhole bh) throws IOException {
        VProcessService.VProcessResult r = proc.execute(
            List.of("true"), Map.of(), workdir);
        return r.exitCode();
    }

    @Benchmark
    public String execute_date_capture(Blackhole bh) throws IOException {
        VProcessService.VProcessResult r = proc.execute(
            List.of("date", "+%s"), Map.of(), workdir);
        return r.stdout().trim();
    }

    @Benchmark
    public void execute_shell_pipeline(Blackhole bh) throws IOException {
        // Single-shell command with pipe — tests sh -c overhead
        proc.execute(List.of("sh", "-c", "echo hello | tr 'a-z' 'A-Z'"), Map.of(), workdir);
    }
}
