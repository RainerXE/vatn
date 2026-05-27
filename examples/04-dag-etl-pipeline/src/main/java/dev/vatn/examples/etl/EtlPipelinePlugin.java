package dev.vatn.examples.etl;

import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.workflow.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ETL pipeline: extract → validate → transform → load → notify
 *                                              └─── cleanup (ALWAYS)
 */
public class EtlPipelinePlugin implements VNodePlugin {

    private VDagEngine engine;
    private VDagRegistry registry;
    private VEventLog eventLog;

    @Override public String getId()      { return "dev.vatn.examples.etl-pipeline"; }
    @Override public String getName()    { return "ETL Pipeline"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        engine    = ctx.getService(VDagEngine.class).orElseThrow();
        registry  = ctx.getService(VDagRegistry.class).orElseThrow();
        eventLog  = ctx.getService(VEventLog.class).orElse(null);

        registerOperators(ctx);
        registerDag();

        // Resume any DAG runs that were interrupted by a previous crash or restart.
        // Tasks that already succeeded will be skipped; only failed/unstarted tasks re-execute.
        engine.resumeInterruptedRuns();

        ctx.register("/etl", new EtlApiService(engine, eventLog));
    }

    private void registerOperators(VNodeContext ctx) {
        registry.registerOperator(new VOperator() {
            public String operatorType() { return "etl.extract"; }
            public String execute(VTaskContext t) {
                String source = t.getConf().getOrDefault("source", "default");
                t.log("Extracting from source: %s", source);
                // Simulate reading 42 records from an external API
                int count = 42;
                t.getXCom().pushReturn(t.getTaskId(), String.valueOf(count));
                return String.valueOf(count);
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "etl.validate"; }
            public String execute(VTaskContext t) throws Exception {
                int count = Integer.parseInt(t.getXCom().pullReturn("extract").orElse("0"));
                if (count == 0) throw new IllegalStateException("No records to process");
                t.log("Validated %d records", count);
                t.getXCom().pushReturn(t.getTaskId(), String.valueOf(count));
                return String.valueOf(count);
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "etl.transform"; }
            public String execute(VTaskContext t) {
                int count = Integer.parseInt(t.getXCom().pullReturn("validate").orElse("0"));
                // Simulate transformation (e.g., normalise, enrich)
                int transformed = count;
                t.log("Transformed %d records", transformed);
                t.getXCom().pushReturn(t.getTaskId(), String.valueOf(transformed));
                return String.valueOf(transformed);
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "etl.load"; }
            public String execute(VTaskContext t) {
                int count = Integer.parseInt(t.getXCom().pullReturn("transform").orElse("0"));
                t.log("Loaded %d rows into database", count);
                t.getXCom().pushReturn(t.getTaskId(), String.valueOf(count));
                return String.valueOf(count);
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "etl.notify"; }
            public String execute(VTaskContext t) {
                int loaded = Integer.parseInt(t.getXCom().pullReturn("load").orElse("0"));
                String msg = String.format("{\"event\":\"etl.complete\",\"rows\":%d,\"dag\":\"%s\",\"run\":\"%s\"}",
                        loaded, t.getDagId(), t.getRunId());
                t.getNodeContext().getMessaging()
                        .publish("etl.complete", msg.getBytes(StandardCharsets.UTF_8));
                t.log("Notification published");
                return msg;
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "etl.cleanup"; }
            public String execute(VTaskContext t) {
                t.log("Cleanup complete (run=%s)", t.getRunId());
                return "cleanup-done";
            }
        });
    }

    private void registerDag() {
        VRetryPolicy retry = new VRetryPolicy(3, 500, 2.0, 5000);

        Map<String, VDagTask> tasks = new LinkedHashMap<>();
        tasks.put("extract",   VDagTask.of("extract",   "etl.extract",   Set.of(),           Map.of()));
        tasks.put("validate",  VDagTask.of("validate",  "etl.validate",  Set.of("extract"),  Map.of()));
        tasks.put("transform", VDagTask.of("transform", "etl.transform", Set.of("validate"), Map.of()));
        tasks.put("load",      new VDagTask("load", "etl.load", Set.of("transform"),
                VTriggerRule.ALL_SUCCESS, retry, VPool.DEFAULT_POOL, 0L, 0, false, 0L, "core", Map.of()));
        tasks.put("notify",    VDagTask.of("notify",    "etl.notify",    Set.of("load"),     Map.of()));
        tasks.put("cleanup",   new VDagTask("cleanup", "etl.cleanup", Set.of("load"),
                VTriggerRule.ALWAYS, VRetryPolicy.NONE, VPool.DEFAULT_POOL, 0L, 0, false, 0L, "core", Map.of()));

        registry.register(VDag.manual("etl-pipeline", "ETL pipeline: extract → load → notify", tasks));
    }

    // ── HTTP control surface ──────────────────────────────────────────────────

    private static class EtlApiService implements VHttpService {
        private final VDagEngine engine;
        private final VEventLog  eventLog;

        EtlApiService(VDagEngine engine, VEventLog eventLog) {
            this.engine   = engine;
            this.eventLog = eventLog;
        }

        @Override
        public void routing(VHttpRoutes routes) {
            routes.post("/trigger", (req, res) -> {
                String body = req.getBody();
                String source = extractField(body, "source");
                String date   = extractField(body, "date");
                Map<String, String> conf = Map.of(
                        "source", source != null ? source : "default",
                        "date",   date   != null ? date   : "today");
                VDagRun run = engine.trigger("etl-pipeline", conf, true);
                res.status(202).sendJson(
                        "{\"runId\":\"" + run.runId() + "\",\"state\":\"" + run.state() + "\"}");
            });

            routes.get("/runs", (req, res) -> {
                var runs = engine.getRuns("etl-pipeline", 20);
                var sb = new StringBuilder("[");
                for (int i = 0; i < runs.size(); i++) {
                    VDagRun r = runs.get(i);
                    if (i > 0) sb.append(",");
                    sb.append("{\"runId\":\"").append(r.runId())
                      .append("\",\"state\":\"").append(r.state()).append("\"}");
                }
                res.sendJson(sb.append("]").toString());
            });

            // Lists runs that were interrupted (crash-safe replay candidates).
            // GET /etl/runs/interrupted
            routes.get("/runs/interrupted", (req, res) -> {
                if (eventLog == null) { res.status(503).send("event log not available"); return; }
                List<String> ids = eventLog.getInterruptedRunIds();
                var sb = new StringBuilder("[");
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(ids.get(i)).append("\"");
                }
                res.sendJson(sb.append("]").toString());
            });

            // Check whether all tasks in a specific run succeeded (useful for observability).
            // GET /etl/runs/{runId}/tasks/{taskId}/succeeded
            routes.get("/runs/{runId}/tasks/{taskId}/succeeded", (req, res) -> {
                if (eventLog == null) { res.status(503).send("event log not available"); return; }
                String runId  = req.getPathParam("runId");
                String taskId = req.getPathParam("taskId");
                boolean ok = eventLog.hasSucceeded(runId, taskId);
                res.sendJson("{\"runId\":\"" + runId + "\",\"taskId\":\"" + taskId
                        + "\",\"succeeded\":" + ok + "}");
            });
        }

        private static String extractField(String json, String field) {
            if (json == null) return null;
            String key = "\"" + field + "\"";
            int idx = json.indexOf(key);
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx + key.length());
            if (colon < 0) return null;
            int start = json.indexOf('"', colon + 1);
            if (start < 0) return null;
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        }
    }
}
