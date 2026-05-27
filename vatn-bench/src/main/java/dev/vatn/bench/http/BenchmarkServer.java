package dev.vatn.bench.http;

import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.core.VNodeRunner;

/**
 * Standalone server for external HTTP throughput benchmarks (wrk, hey, etc.).
 *
 * Registers the same EchoService used in HttpServerBench so that
 * bench/http/run.sh can drive wrk against real network sockets.
 *
 * Usage: java -cp vatn-benchmarks.jar dev.vatn.bench.http.BenchmarkServer [port]
 */
public class BenchmarkServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        VNodeRunner runner = VNodeRunner.create(port);
        runner.register("/bench", (VHttpService) routes -> routes
            .get("/ping",  (req, res) -> res.send("pong"))
            .get("/json",  (req, res) -> res.sendJson("{\"runtime\":\"vatn\",\"status\":\"ok\"}"))
            .post("/echo", (req, res) -> res.sendJson("{\"echo\":\"" + req.getBody() + "\"}"))
        );
        runner.start();

        System.out.println("[VATN-BENCH] BenchmarkServer running on port " + runner.getBoundPort());
        System.out.println("[VATN-BENCH] Endpoints: /bench/ping  /bench/json  /bench/echo");
        System.out.println("[VATN-BENCH] Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(runner::stop));
        Thread.currentThread().join(); // block until SIGTERM
    }
}
