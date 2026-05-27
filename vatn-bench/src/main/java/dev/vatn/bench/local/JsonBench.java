package dev.vatn.bench.local;

import dev.vatn.api.VJson;
import dev.vatn.bench.VNodeState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * VJson throughput: stringify, parse, query, queryArray.
 * Comparable to BenchCraft JSON benchmarks.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class JsonBench extends VNodeState {

    private VJson json;

    private Object smallObj;
    private Object mediumObj;
    private String smallJson;
    private String mediumJson;
    private String arrayJson;

    @Setup(Level.Trial)
    @Override
    public void startNode() throws Exception {
        super.startNode();
        json = context.getJson();

        smallObj = Map.of("name", "VATN", "version", 1, "active", true, "score", 99.7);
        smallJson = json.stringify(smallObj);

        // ~50 field object simulating a typical config/event payload
        Map<String, Object> medium = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) medium.put("field_" + i, "value_" + i);
        medium.put("count", 1000);
        medium.put("enabled", true);
        mediumObj = medium;
        mediumJson = json.stringify(mediumObj);

        arrayJson = json.stringify(Map.of("tags", List.of("java", "vatn", "runtime", "benchmark", "fast")));
    }

    @Benchmark
    public String stringify_small(Blackhole bh) {
        return json.stringify(smallObj);
    }

    @Benchmark
    public String stringify_medium(Blackhole bh) {
        return json.stringify(mediumObj);
    }

    @Benchmark
    @SuppressWarnings("unchecked")
    public Object parse_small(Blackhole bh) {
        return json.parse(smallJson, Map.class);
    }

    @Benchmark
    @SuppressWarnings("unchecked")
    public Object parse_medium(Blackhole bh) {
        return json.parse(mediumJson, Map.class);
    }

    @Benchmark
    public String query_top_level(Blackhole bh) {
        return json.query(smallJson, "name");
    }

    @Benchmark
    public int queryInt_field(Blackhole bh) {
        return json.queryInt(smallJson, "version", 0);
    }

    @Benchmark
    public List<String> queryArray_field(Blackhole bh) {
        return json.queryArray(arrayJson, "tags");
    }
}
