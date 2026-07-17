package dev.vatn.demo.taskqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.workflow.*;
import dev.vatn.demo.taskqueue.model.Order;
import dev.vatn.demo.taskqueue.operators.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Order processing pipeline plugin.
 *
 * Registers the order-pipeline DAG and all five operators, then exposes a small
 * REST API that lets callers submit orders and inspect run progress.
 *
 * DAG topology:
 * <pre>
 *   validate-order
 *         |
 *   charge-payment   ← 3-attempt retry, 2 s initial delay, 2x backoff
 *         |
 *    ┌────┴────┐
 * update-inv  send-confirm   ← parallel fan-out
 *    |
 * notify-ship
 * </pre>
 */
public class OrderPipelinePlugin implements VNodePlugin {

    static final String DAG_ID = "order-pipeline";

    private VDagEngine engine;

    @Override
    public String getId() {
        return "dev.vatn.demo.order-pipeline";
    }

    @Override
    public String getName() {
        return "Order Pipeline";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onInitialize(VNodeContext ctx) {
        engine = ctx.getService(VDagEngine.class).orElseThrow(
            () -> new IllegalStateException("VDagEngine service not available"));
        VDagRegistry registry = ctx.getService(VDagRegistry.class).orElseThrow(
            () -> new IllegalStateException("VDagRegistry service not available"));

        registerOperators(registry);
        registerDag(registry);

        // Resume any runs that were in-flight when the JVM last stopped.
        // The engine skips tasks that already succeeded — only incomplete work re-executes.
        engine.resumeInterruptedRuns();

        ctx.register("/orders", new OrderApiService(engine));
    }

    // ── Operator registration ─────────────────────────────────────────────────

    private void registerOperators(VDagRegistry registry) {
        registry.registerOperator(new ValidateOrderOperator());
        registry.registerOperator(new ChargePaymentOperator());
        registry.registerOperator(new UpdateInventoryOperator());
        registry.registerOperator(new SendConfirmationOperator());
        registry.registerOperator(new NotifyShippingOperator());
    }

    // ── DAG definition ────────────────────────────────────────────────────────

    private void registerDag(VDagRegistry registry) {
        // Payment retries: 3 attempts, 2s → 4s → capped at 30s
        VRetryPolicy paymentRetry = new VRetryPolicy(3, 2_000, 2.0, 30_000);

        Map<String, VDagTask> tasks = new LinkedHashMap<>();

        tasks.put("validate-order",
            VDagTask.of("validate-order", "order.validate", Set.of(), Map.of()));

        tasks.put("charge-payment",
            new VDagTask("charge-payment", "order.charge-payment",
                Set.of("validate-order"),
                VTriggerRule.ALL_SUCCESS, paymentRetry,
                VPool.DEFAULT_POOL, 0L, 0, false, 0L, null, Map.of()));

        tasks.put("update-inventory",
            VDagTask.of("update-inventory", "order.update-inventory",
                Set.of("charge-payment"), Map.of()));

        tasks.put("send-confirmation",
            VDagTask.of("send-confirmation", "order.send-confirmation",
                Set.of("charge-payment"), Map.of()));

        tasks.put("notify-shipping",
            VDagTask.of("notify-shipping", "order.notify-shipping",
                Set.of("update-inventory"), Map.of()));

        registry.register(VDag.manual(DAG_ID,
            "E-commerce order pipeline: validate → charge → (inventory | confirm) → ship",
            tasks));
    }

    // ── REST API ──────────────────────────────────────────────────────────────

    private static class OrderApiService implements VHttpService {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final VDagEngine engine;

        OrderApiService(VDagEngine engine) {
            this.engine = engine;
        }

        @Override
        public void routing(VHttpRoutes routes) {

            // POST /orders — submit a new order; body must be an Order JSON object
            routes.post("", (req, res) -> {
                String body = req.getBody();
                if (body == null || body.isBlank()) {
                    res.status(400).send("{\"error\":\"request body is empty\"}");
                    return;
                }

                // Quick structural check before triggering the DAG
                try {
                    MAPPER.readValue(body, Order.class);
                } catch (Exception e) {
                    res.status(400).send("{\"error\":\"invalid Order JSON: " + escape(e.getMessage()) + "\"}");
                    return;
                }

                VDagRun run = engine.trigger(DAG_ID, Map.of("order", body), true);
                res.status(202).sendJson(
                    "{\"runId\":\"" + run.runId() + "\",\"status\":\"QUEUED\"}");
            });

            // GET /orders — last 20 runs
            routes.get("", (req, res) -> {
                List<VDagRun> runs = engine.getRuns(DAG_ID, 20);
                res.sendJson(runsToJson(runs));
            });

            // GET /orders/{runId} — run detail with task states
            routes.get("/{runId}", (req, res) -> {
                String runId = req.getPathParam("runId");
                var maybeRun = engine.getRunById(runId);
                if (maybeRun.isEmpty()) {
                    res.status(404).send("{\"error\":\"run not found\"}");
                    return;
                }
                VDagRun run = maybeRun.get();
                List<VTaskInstance> tasks = engine.getTaskInstances(runId);
                res.sendJson(runDetailToJson(run, tasks));
            });

            // DELETE /orders/{runId} — cancel a run
            routes.delete("/{runId}", (req, res) -> {
                String runId = req.getPathParam("runId");
                var maybeRun = engine.getRunById(runId);
                if (maybeRun.isEmpty()) {
                    res.status(404).send("{\"error\":\"run not found\"}");
                    return;
                }
                engine.cancel(runId);
                res.status(200).sendJson("{\"runId\":\"" + runId + "\",\"status\":\"CANCELED\"}");
            });
        }

        // ── JSON helpers ──────────────────────────────────────────────────────

        private String runsToJson(List<VDagRun> runs) {
            var sb = new StringBuilder("[");
            for (int i = 0; i < runs.size(); i++) {
                if (i > 0) sb.append(",");
                VDagRun r = runs.get(i);
                sb.append("{\"runId\":\"").append(r.runId()).append("\"")
                  .append(",\"state\":\"").append(r.state()).append("\"")
                  .append(",\"logicalDate\":\"").append(r.logicalDate()).append("\"")
                  .append("}");
            }
            return sb.append("]").toString();
        }

        private String runDetailToJson(VDagRun run, List<VTaskInstance> tasks) {
            var sb = new StringBuilder("{");
            sb.append("\"runId\":\"").append(run.runId()).append("\"");
            sb.append(",\"state\":\"").append(run.state()).append("\"");
            sb.append(",\"logicalDate\":\"").append(run.logicalDate()).append("\"");
            if (run.startDate() != null) {
                sb.append(",\"startDate\":\"").append(run.startDate()).append("\"");
            }
            if (run.endDate() != null) {
                sb.append(",\"endDate\":\"").append(run.endDate()).append("\"");
            }
            sb.append(",\"tasks\":[");
            for (int i = 0; i < tasks.size(); i++) {
                if (i > 0) sb.append(",");
                VTaskInstance t = tasks.get(i);
                sb.append("{\"taskId\":\"").append(t.taskId()).append("\"")
                  .append(",\"state\":\"").append(t.state()).append("\"")
                  .append(",\"tryNumber\":").append(t.tryNumber());
                if (t.startDate() != null) {
                    sb.append(",\"startDate\":\"").append(t.startDate()).append("\"");
                }
                if (t.endDate() != null) {
                    sb.append(",\"endDate\":\"").append(t.endDate()).append("\"");
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\"", "'").replace("\n", " ").replace("\r", "");
        }
    }
}
