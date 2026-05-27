package dev.vatn.bench.local;

import dev.vatn.api.VFileService;
import dev.vatn.bench.VNodeState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * VFileService read/write/list throughput for small (1KB) and medium (1MB) files.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class FileBench extends VNodeState {

    private VFileService fs;
    private Path smallFile;
    private Path mediumFile;
    private Path dir;
    private String smallContent;
    private String mediumContent;

    @Setup(Level.Trial)
    @Override
    public void startNode() throws Exception {
        super.startNode();
        fs = context.getService(VFileService.class).orElseThrow();

        Path tmp = Files.createTempDirectory("vatn-bench-");
        dir = tmp;
        smallFile  = tmp.resolve("small.txt");
        mediumFile = tmp.resolve("medium.txt");

        smallContent  = "x".repeat(1024);          // 1 KB
        mediumContent = "x".repeat(1024 * 1024);   // 1 MB

        fs.writeString(smallFile, smallContent);
        fs.writeString(mediumFile, mediumContent);

        // Populate dir with 20 files for list benchmark
        for (int i = 0; i < 20; i++) fs.writeString(tmp.resolve("f" + i + ".txt"), "data");
    }

    @TearDown(Level.Trial)
    @Override
    public void stopNode() throws Exception {
        // Clean up temp dir before stopping runner
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
        super.stopNode();
    }

    @Benchmark
    public String read_1KB(Blackhole bh) throws IOException {
        return fs.readString(smallFile);
    }

    @Benchmark
    public String read_1MB(Blackhole bh) throws IOException {
        return fs.readString(mediumFile);
    }

    @Benchmark
    public void write_1KB() throws IOException {
        fs.writeString(smallFile, smallContent);
    }

    @Benchmark
    public void write_1MB() throws IOException {
        fs.writeString(mediumFile, mediumContent);
    }

    @Benchmark
    public List<Path> list_dir(Blackhole bh) throws IOException {
        return fs.list(dir);
    }

    @Benchmark
    public boolean exists_check(Blackhole bh) {
        return fs.exists(smallFile);
    }
}
