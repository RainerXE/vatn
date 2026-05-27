package dev.vatn.bench.http;

import dev.vatn.api.*;
import dev.vatn.bench.VNodeState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * VHttpService in-process throughput: measures how fast the VATN HTTP stack
 * can dispatch a request through VHttpRoutes to a handler.
 *
 * For true external throughput numbers (req/sec with real network round-trips),
 * use bench/http-throughput.sh which drives wrk against a running BenchmarkServer.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class HttpServerBench extends VNodeState {

    private VHttpClient httpClient;
    private int port;

    /** Minimal echo service registered into the running node */
    static class EchoService implements VHttpService {
        @Override
        public void routing(VHttpRoutes routes) {
            routes
                .get("/ping",   (req, res) -> res.send("pong"))
                .post("/echo",  (req, res) -> res.sendJson("{\"echo\":\"" + req.getBody() + "\"}"))
                .get("/json",   (req, res) -> res.sendJson("{\"runtime\":\"vatn\",\"status\":\"ok\"}"));
        }
    }

    @Setup(Level.Trial)
    @Override
    public void startNode() throws Exception {
        super.startNode();
        context.register("/bench", new EchoService());
        httpClient = context.getService(VHttpClient.class).orElseThrow();
        port = runner.getBoundPort();
    }

    @Benchmark
    public String get_ping(Blackhole bh) throws IOException {
        return httpClient.get("http://localhost:" + port + "/bench/ping").body();
    }

    @Benchmark
    public String get_json(Blackhole bh) throws IOException {
        return httpClient.get("http://localhost:" + port + "/bench/json").body();
    }

    @Benchmark
    public String post_echo(Blackhole bh) throws IOException {
        return httpClient.post(
            "http://localhost:" + port + "/bench/echo",
            "{\"msg\":\"hello\"}",
            "application/json"
        ).body();
    }
}
