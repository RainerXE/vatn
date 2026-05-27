package dev.vatn.examples.report;

import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VPersistenceService;
import dev.vatn.api.workflow.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cron-scheduled nightly report: query DB → aggregate → render → write file → notify.
 * Schedule: "0 2 * * *" (02:00 UTC daily). Trigger manually via POST /report/trigger.
 */
public class ScheduledReportPlugin implements VNodePlugin {

    private VDagEngine engine;
    private VDagRegistry registry;

    @Override public String getId()      { return "dev.vatn.examples.scheduled-report"; }
    @Override public String getName()    { return "Scheduled Report"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        engine   = ctx.getService(VDagEngine.class).orElseThrow();
        registry = ctx.getService(VDagRegistry.class).orElseThrow();

        VPersistenceService db = ctx.getService(VPersistenceService.class).orElseThrow();
        db.registerSchemaContributor(st -> st.execute("""
                    CREATE TABLE IF NOT EXISTS daily_events (
                        id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        day     TEXT NOT NULL,
                        type    TEXT NOT NULL,
                        count   INTEGER NOT NULL DEFAULT 1
                    )
                    """));

        // Seed sample data after schema is ready (contributor runs synchronously above)
        try (var conn = db.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM daily_events")) {
            if (rs.next() && rs.getInt(1) == 0) {
                conn.createStatement().execute("""
                    INSERT INTO daily_events (day, type, count) VALUES
                    (date('now'), 'page_view', 1024),
                    (date('now'), 'api_call',  512),
                    (date('now'), 'error',     7)
                    """);
            }
        } catch (java.sql.SQLException e) {
            // Non-fatal — sample data is optional
        }

        registerOperators(ctx);
        registerDag();

        ctx.register("/report", new ReportApiService(engine, ctx.getWorkspacePath()));
    }

    private void registerOperators(VNodeContext ctx) {
        VPersistenceService db = ctx.getService(VPersistenceService.class).orElseThrow();

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "report.query_db"; }
            public String execute(VTaskContext t) throws Exception {
                String date = t.getConf().getOrDefault("date", LocalDate.now().toString());
                var rows = new StringBuilder("[");
                try (var conn = db.getConnection();
                     var ps = conn.prepareStatement(
                             "SELECT type, SUM(count) FROM daily_events WHERE day=? GROUP BY type")) {
                    ps.setString(1, date);
                    var rs = ps.executeQuery();
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) rows.append(",");
                        rows.append("{\"type\":\"").append(rs.getString(1))
                            .append("\",\"count\":").append(rs.getLong(2)).append("}");
                        first = false;
                    }
                }
                String result = rows.append("]").toString();
                t.getXCom().pushReturn(t.getTaskId(), result);
                t.log("Queried %s: %s", date, result);
                return result;
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "report.aggregate"; }
            public String execute(VTaskContext t) {
                String rows = t.getXCom().pullReturn("query_db").orElse("[]");
                // Count total events (simple approach — no JSON lib dependency in examples)
                long total = rows.chars().filter(c -> c == '{').count();
                String summary = "{\"rows\":" + total + ",\"raw\":\"" + rows.replace("\"", "\\\"") + "\"}";
                t.getXCom().pushReturn(t.getTaskId(), summary);
                t.log("Aggregated %d row types", total);
                return summary;
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "report.render_markdown"; }
            public String execute(VTaskContext t) {
                String date = t.getConf().getOrDefault("date", LocalDate.now().toString());
                String raw  = t.getXCom().pullReturn("query_db").orElse("[]");
                String md = "# Daily Report — " + date + "\n\n"
                        + "Generated by VATN Scheduled Report plugin.\n\n"
                        + "## Data\n\n"
                        + "```json\n" + raw + "\n```\n\n"
                        + "_Run ID: " + t.getRunId() + "_\n";
                t.getXCom().pushReturn(t.getTaskId(), md);
                t.log("Markdown rendered (%d chars)", md.length());
                return md;
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "report.write_file"; }
            public String execute(VTaskContext t) throws IOException {
                String date     = t.getConf().getOrDefault("date", LocalDate.now().toString());
                String markdown = t.getXCom().pullReturn("render_markdown").orElse("");
                Path reportsDir = t.getNodeContext().getWorkspacePath().resolve("reports");
                Files.createDirectories(reportsDir);
                Path file = reportsDir.resolve(date + ".md");
                Files.writeString(file, markdown);
                String path = file.toString();
                t.getXCom().pushReturn(t.getTaskId(), path);
                t.log("Report written to %s", path);
                return path;
            }
        });

        registry.registerOperator(new VOperator() {
            public String operatorType() { return "report.notify"; }
            public String execute(VTaskContext t) {
                String path = t.getXCom().pullReturn("write_file").orElse("unknown");
                String msg  = "{\"event\":\"report.ready\",\"path\":\"" + path.replace("\\", "\\\\") + "\"}";
                t.getNodeContext().getMessaging()
                        .publish("reports.ready", msg.getBytes(StandardCharsets.UTF_8));
                t.log("Published reports.ready");
                return path;
            }
        });
    }

    private void registerDag() {
        Map<String, VDagTask> tasks = new LinkedHashMap<>();
        tasks.put("query_db",        VDagTask.of("query_db",        "report.query_db",        Set.of(),              Map.of()));
        tasks.put("aggregate",        VDagTask.of("aggregate",        "report.aggregate",        Set.of("query_db"),    Map.of()));
        tasks.put("render_markdown",  VDagTask.of("render_markdown",  "report.render_markdown",  Set.of("query_db"),    Map.of()));
        tasks.put("write_file",       VDagTask.of("write_file",       "report.write_file",       Set.of("render_markdown"), Map.of()));
        tasks.put("notify",           VDagTask.of("notify",           "report.notify",           Set.of("write_file"),  Map.of()));

        // Runs nightly at 02:00 UTC; VDagScheduler evaluates this automatically
        registry.register(VDag.scheduled("nightly-report", "Nightly summary report", "0 2 * * *", tasks));
    }

    // ── HTTP control surface ──────────────────────────────────────────────────

    private static class ReportApiService implements VHttpService {
        private final VDagEngine engine;
        private final Path workspace;

        ReportApiService(VDagEngine engine, Path workspace) {
            this.engine = engine;
            this.workspace = workspace;
        }

        @Override
        public void routing(VHttpRoutes routes) {
            routes.post("/trigger", (req, res) -> {
                String date = LocalDate.now().toString();
                VDagRun run = engine.trigger("nightly-report",
                        Map.of("date", date), true);
                res.status(202).sendJson(
                        "{\"runId\":\"" + run.runId() + "\",\"date\":\"" + date + "\"}");
            });

            routes.get("/runs", (req, res) -> {
                var runs = engine.getRuns("nightly-report", 10);
                var sb = new StringBuilder("[");
                for (int i = 0; i < runs.size(); i++) {
                    if (i > 0) sb.append(",");
                    VDagRun r = runs.get(i);
                    sb.append("{\"runId\":\"").append(r.runId())
                      .append("\",\"state\":\"").append(r.state()).append("\"}");
                }
                res.sendJson(sb.append("]").toString());
            });

            routes.get("/files", (req, res) -> {
                Path reportsDir = workspace.resolve("reports");
                var sb = new StringBuilder("[");
                try {
                    if (Files.exists(reportsDir)) {
                        var files = Files.list(reportsDir)
                                .filter(p -> p.toString().endsWith(".md"))
                                .sorted()
                                .toList();
                        for (int i = 0; i < files.size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append("\"").append(files.get(i).getFileName()).append("\"");
                        }
                    }
                } catch (IOException e) { /* ignore */ }
                res.sendJson(sb.append("]").toString());
            });
        }
    }
}
