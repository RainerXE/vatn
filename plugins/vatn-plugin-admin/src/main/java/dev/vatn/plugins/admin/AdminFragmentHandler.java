package dev.vatn.plugins.admin;

import dev.vatn.api.*;
import dev.vatn.api.admin.*;
import dev.vatn.api.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

class AdminFragmentHandler {
    private static final Logger log = LoggerFactory.getLogger(AdminFragmentHandler.class);

    private final VNodeContext ctx;
    private final AdminConfig config;
    private final long startedAt;

    AdminFragmentHandler(VNodeContext ctx, AdminConfig config, long startedAt) {
        this.ctx = ctx;
        this.config = config;
        this.startedAt = startedAt;
    }

    void overview(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        long uptimeMs = System.currentTimeMillis() - startedAt;
        VConfiguration conf = ctx.getConfiguration();
        String flavor = conf != null && conf.isAot() ? "AOT (GraalVM)" : "JVM";
        int pluginCount = ctx.getService(VPluginRegistry.class)
                .map(r -> r.getPlugins().size()).orElse(0);
        int agentCount = ctx.getAgentInfos().size();

        String html = div("space-y-1 text-xs",
                kv("Node ID",  "<span class=\"font-mono text-blue-300\">" + esc(ctx.getNodeId()) + "</span>") +
                kv("Flavor",   esc(flavor)) +
                kv("Uptime",   esc(formatUptime(uptimeMs))) +
                kv("VATN",     "1.0") +
                kv("Plugins",  String.valueOf(pluginCount)) +
                kv("Agents",   String.valueOf(agentCount))
        );
        res.sendHtml(html);
    }

    void plugins(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        Optional<VPluginManager> mgrOpt = ctx.getService(VPluginManager.class);
        List<Map<String, Object>> statuses;
        if (mgrOpt.isPresent()) {
            statuses = mgrOpt.get().getStatuses().stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", s.id());
                        m.put("name", s.name());
                        m.put("version", s.version());
                        m.put("state", s.state().name());
                        m.put("lastError", s.lastError());
                        return m;
                    }).collect(Collectors.toList());
        } else {
            statuses = ctx.getService(VPluginRegistry.class)
                    .map(r -> r.getPlugins().stream()
                            .map(p -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("id", p.getId());
                                m.put("name", p.getName());
                                m.put("version", p.getVersion());
                                m.put("state", "RUNNING");
                                m.put("lastError", null);
                                return m;
                            }).collect(Collectors.toList()))
                    .orElse(List.of());
        }

        if (statuses.isEmpty()) {
            res.sendHtml("<span class=\"text-gray-600\">No plugins registered</span>");
            return;
        }

        final String ADMIN_ID = "dev.vatn.plugins.admin";
        StringBuilder rows = new StringBuilder();
        for (var p : statuses) {
            String id = (String) p.get("id");
            String shortId = id.replace("dev.vatn.plugins.", "");
            String name = (String) p.get("name");
            String version = (String) p.get("version");
            String state = (String) p.get("state");
            String lastError = (String) p.get("lastError");
            boolean isStopped = "STOPPED".equals(state);
            boolean isAdmin = ADMIN_ID.equals(id);
            String stopDis = (isStopped || isAdmin) ? " disabled" : "";

            rows.append("<tr class=\"border-b border-gray-800/50\">")
                .append("<td class=\"py-1.5 pr-4 text-gray-300 font-mono\">").append(esc(shortId)).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-400\">").append(esc(name)).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-600\">").append(esc(version)).append("</td>")
                .append("<td class=\"py-1.5 pr-4\">").append(pluginStateBadge(state)).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-red-400 max-w-xs truncate\">").append(lastError != null ? esc(lastError) : "").append("</td>")
                .append("<td class=\"py-1.5 whitespace-nowrap\">")
                .append("<button id=\"btn-restart-").append(sanitizeHtmlAttr(id)).append("\" onclick=\"restartPlugin('").append(esc(id)).append("')\" title=\"Restart\"")
                .append(" class=\"text-yellow-400 hover:text-yellow-300 transition px-1.5 disabled:opacity-30\">&#8634;</button>")
                .append("<button id=\"btn-stop-").append(sanitizeHtmlAttr(id)).append("\" onclick=\"stopPlugin('").append(esc(id)).append("')\" title=\"Stop\"")
                .append(stopDis).append(" class=\"text-red-400 hover:text-red-300 transition px-1.5 disabled:opacity-30\">&#9632;</button>")
                .append("</td></tr>");
        }

        String html = "<table class=\"w-full\">" +
            "<thead><tr class=\"text-gray-600 text-left border-b border-gray-800\">" +
            "<th class=\"pb-2 pr-4 font-normal\">ID</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Name</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Version</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">State</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Error</th>" +
            "<th class=\"pb-2 font-normal\">Actions</th>" +
            "</tr></thead><tbody>" + rows + "</tbody></table>";
        res.sendHtml(html);
    }

    void health(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        var agents = ctx.getAgentInfos();
        if (agents.isEmpty()) {
            res.sendHtml("<span class=\"text-gray-600\">No health checks registered</span>");
            return;
        }
        StringBuilder html = new StringBuilder();
        for (var agent : agents) {
            boolean up = agent.role() == VAgentRole.PRIMARY || agent.role() == VAgentRole.TWIN;
            String dot = up
                ? "<span class=\"text-green-400\">&#9679;</span>"
                : "<span class=\"text-yellow-400\">&#9680;</span>";
            html.append("<div class=\"flex items-center gap-2\">")
                .append(dot)
                .append("<span class=\"text-gray-300\">agent.").append(esc(agent.id())).append("</span>")
                .append("<span class=\"ml-auto text-gray-500\">").append(esc(agent.role().name())).append("</span>")
                .append("</div>");
        }
        res.sendHtml(div("space-y-1 text-xs", html.toString()));
    }

    void workflows(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        List<Map<String, Object>> result = new ArrayList<>();
        Optional<VDagRegistry> regOpt    = ctx.getService(VDagRegistry.class);
        Optional<VDagEngine>   engineOpt = ctx.getService(VDagEngine.class);
        if (regOpt.isPresent() && engineOpt.isPresent()) {
            VDagEngine engine = engineOpt.get();
            engine.listActiveRuns().forEach(r -> result.add(AdminPlugin.runToMap(r)));
            regOpt.get().listDags().forEach(dag ->
                    engine.getRuns(dag.id(), 20).stream()
                            .filter(r -> r.state() != VDagRunState.RUNNING
                                      && r.state() != VDagRunState.QUEUED)
                            .forEach(r -> result.add(AdminPlugin.runToMap(r))));
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            result.removeIf(m -> !seen.add((String) m.get("runId")));
        }

        if (result.isEmpty()) {
            res.sendHtml("<span class=\"text-gray-600\">No workflow runs found</span>");
            return;
        }

        StringBuilder rows = new StringBuilder();
        for (var r : result) {
            String runId = (String) r.get("runId");
            String dagId = (String) r.get("dagId");
            String state = (String) r.get("state");
            Number durationMs = (Number) r.get("durationMs");
            String durationStr = durationMs != null ? String.format("%.1fs", durationMs.doubleValue() / 1000) : "&#8212;";
            String triggered = (String) r.get("triggered");
            String started = r.get("started") != null ? r.get("started").toString() : "&#8212;";

            rows.append("<tr class=\"border-b border-gray-800/50\">")
                .append("<td class=\"py-1.5 pr-4 text-gray-500 font-mono\">").append(esc(runId.substring(0, Math.min(8, runId.length())))).append("&#8230;</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-300\">").append(esc(dagId)).append("</td>")
                .append("<td class=\"py-1.5 pr-4\">").append(workflowStateBadge(state)).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-500\">").append(durationStr).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-600\">").append(esc(triggered)).append("</td>")
                .append("<td class=\"py-1.5 text-gray-600\">").append(started).append("</td></tr>");
        }

        String html = "<table class=\"w-full\">" +
            "<thead><tr class=\"text-gray-600 text-left border-b border-gray-800\">" +
            "<th class=\"pb-2 pr-4 font-normal\">Run</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">DAG</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">State</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Duration</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Trigger</th>" +
            "<th class=\"pb-2 font-normal\">Started</th>" +
            "</tr></thead><tbody>" + rows + "</tbody></table>";
        res.sendHtml(html);
    }

    void workloads(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        var registryOpt = ctx.getService(VWorkloadRegistry.class);
        if (registryOpt.isEmpty()) {
            res.sendHtml("<span class=\"text-gray-600\">VWorkloadRegistry not available</span>");
            return;
        }
        List<VWorkload> workloads = registryOpt.get().getAllWorkloads();
        if (workloads.isEmpty()) {
            res.sendHtml("<span class=\"text-gray-600\">No active workloads</span>");
            return;
        }

        StringBuilder rows = new StringBuilder();
        for (var w : workloads) {
            String id     = w.id();
            String name   = w.name();
            String type   = w.type().name();
            String status = w.status().name();
            String started = w.startTime() != null ? w.startTime().toString() : "&#8212;";
            String resources = formatResources(w.resourceUsage());

            rows.append("<tr class=\"border-b border-gray-800/50\">")
                .append("<td class=\"py-1.5 pr-4 text-gray-500 font-mono\">").append(esc(id)).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-300\">").append(esc(name)).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-500\">").append(esc(type)).append("</td>")
                .append("<td class=\"py-1.5 pr-4\">").append(workloadStatusBadge(status)).append("</td>")
                .append("<td class=\"py-1.5 pr-4 text-gray-600\">").append(started).append("</td>")
                .append("<td class=\"py-1.5 text-gray-500\">").append(esc(resources)).append("</td></tr>");
        }

        String html = "<table class=\"w-full\">" +
            "<thead><tr class=\"text-gray-600 text-left border-b border-gray-800\">" +
            "<th class=\"pb-2 pr-4 font-normal\">ID</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Name</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Type</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Status</th>" +
            "<th class=\"pb-2 pr-4 font-normal\">Started</th>" +
            "<th class=\"pb-2 font-normal\">Resources</th>" +
            "</tr></thead><tbody>" + rows + "</tbody></table>";
        res.sendHtml(html);
    }

    void jvm(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        Map<String, Object> data = collectJvmData();

        Map<String, Object> heap = (Map<String, Object>) data.get("heap");
        Map<String, Object> nonHeap = (Map<String, Object>) data.get("nonHeap");
        List<Map<String, Object>> gcList = (List<Map<String, Object>>) data.get("gc");
        Map<String, Object> cpu = (Map<String, Object>) data.get("cpu");
        Map<String, Object> threads = (Map<String, Object>) data.get("threads");
        Map<String, Object> runtime = (Map<String, Object>) data.get("runtime");

        int heapPct = heap != null ? ((Number) heap.getOrDefault("pct", 0)).intValue() : 0;
        long heapUsedMb = heap != null ? ((Number) heap.getOrDefault("usedMb", 0)).longValue() : 0;
        long heapMaxMb = heap != null ? ((Number) heap.getOrDefault("maxMb", 0)).longValue() : 0;
        long nonHeapUsedMb = nonHeap != null ? ((Number) nonHeap.getOrDefault("usedMb", 0)).longValue() : 0;

        String memHtml = kv("Heap used", heapUsedMb + " / " + (heapMaxMb > 0 ? heapMaxMb : "?") + " MB (" + heapPct + "%)") +
            progressBar(heapPct) +
            kv("Non-heap", nonHeapUsedMb + " MB");

        if (gcList != null && !gcList.isEmpty()) {
            StringBuilder gcHtml = new StringBuilder("<div class=\"pt-2 mt-1 border-t border-gray-800 space-y-0.5\">");
            for (var g : gcList) {
                gcHtml.append("<div class=\"flex justify-between text-gray-500\">")
                    .append("<span>").append(esc((String) g.get("name"))).append("</span>")
                    .append("<span>").append(g.get("collectionCount")).append("&#215; &nbsp;").append(g.get("collectionTimeMs")).append("ms</span>")
                    .append("</div>");
            }
            gcHtml.append("</div>");
            memHtml += gcHtml.toString();
        }

        // Performance panel
        String perfHtml;
        double processCpuPct = cpu != null ? ((Number) cpu.getOrDefault("processCpuPct", -1)).doubleValue() : -1;
        double systemCpuPct = cpu != null ? ((Number) cpu.getOrDefault("systemCpuPct", -1)).doubleValue() : -1;
        if (processCpuPct >= 0) {
            perfHtml = kv("Process CPU", processCpuPct + "%") + progressBar((int) Math.round(processCpuPct)) +
                kv("System CPU", systemCpuPct + "%") + progressBar((int) Math.round(systemCpuPct), "bg-purple-500");
        } else {
            double loadAvg = cpu != null ? ((Number) cpu.getOrDefault("loadAvg", 0)).doubleValue() : 0;
            perfHtml = kv("Load avg", String.format("%.2f", loadAvg));
        }
        int processors = cpu != null ? ((Number) cpu.getOrDefault("processors", 0)).intValue() : 0;
        perfHtml += kv("Processors", String.valueOf(processors));

        if (threads != null) {
            perfHtml += "<div class=\"pt-2 mt-1 border-t border-gray-800\">" +
                kv("Threads live",   String.valueOf(threads.get("live"))) +
                kv("Threads peak",   String.valueOf(threads.get("peak"))) +
                kv("Threads daemon", String.valueOf(threads.get("daemon"))) +
                "</div>";
        }

        if (runtime != null) {
            String jvmName = (String) runtime.get("jvmName");
            String jvmVersion = (String) runtime.get("jvmVersion");
            long uptimeMsRt = ((Number) runtime.getOrDefault("uptimeMs", 0)).longValue();
            perfHtml += "<div class=\"pt-2 mt-1 border-t border-gray-800\">" +
                kv("JVM", esc(jvmName + " " + jvmVersion)) +
                kv("JVM uptime", String.format("%.1f min", uptimeMsRt / 60000.0)) +
                "</div>";
        }

        String html = "<div class=\"text-xs space-y-2\">" + memHtml + "</div>" +
            "<div class=\"text-xs space-y-2 pt-4\">" + perfHtml + "</div>";
        res.sendHtml(html);
    }

    void queues(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        Optional<VPersistenceService> dbOpt = ctx.getService(VPersistenceService.class);
        Optional<VQueueService> qsOpt = ctx.getService(VQueueService.class);
        List<Map<String, Object>> queues;
        if (dbOpt.isEmpty() || qsOpt.isEmpty()) {
            queues = List.of();
        } else {
            queues = collectQueueStats(dbOpt.get());
        }

        if (queues.isEmpty()) {
            res.sendHtml("<span class=\"text-gray-600\">No named queues found</span>");
            return;
        }

        StringBuilder rows = new StringBuilder();
        for (var q : queues) {
            String name = (String) q.get("name");
            long pending = ((Number) q.getOrDefault("pending", 0)).longValue();
            long claimed = ((Number) q.getOrDefault("claimed", 0)).longValue();
            long done    = ((Number) q.getOrDefault("done", 0)).longValue();
            long dead    = ((Number) q.getOrDefault("dead", 0)).longValue();

            rows.append("<tr class=\"border-b border-gray-800/50\">")
                .append("<td class=\"py-1.5 pr-4 text-gray-300 font-mono\">").append(esc(name)).append("</td>")
                .append("<td class=\"py-1.5 pr-3\">").append(qbadge("pending", pending, "bg-blue-500/20 text-blue-300")).append("</td>")
                .append("<td class=\"py-1.5 pr-3\">").append(qbadge("claimed", claimed, "bg-yellow-500/20 text-yellow-300")).append("</td>")
                .append("<td class=\"py-1.5 pr-3\">").append(qbadge("done", done, "bg-green-500/20 text-green-400")).append("</td>")
                .append("<td class=\"py-1.5\">").append(qbadge("dead", dead, "bg-red-500/20 text-red-400")).append("</td></tr>");
        }

        String html = "<table class=\"w-full\">" +
            "<thead><tr class=\"text-gray-600 text-left border-b border-gray-800\">" +
            "<th class=\"pb-2 pr-4 font-normal\">Queue</th>" +
            "<th class=\"pb-2 pr-3 font-normal\">Pending</th>" +
            "<th class=\"pb-2 pr-3 font-normal\">Claimed</th>" +
            "<th class=\"pb-2 pr-3 font-normal\">Done</th>" +
            "<th class=\"pb-2 font-normal\">Dead</th>" +
            "</tr></thead><tbody>" + rows + "</tbody></table>";
        res.sendHtml(html);
    }

    void routes(VHttpRequest req, VHttpResponse res) {
        if (!authorized(req, res)) return;
        List<String> routes = ctx.getRegisteredRoutes();
        if (routes.isEmpty()) {
            res.sendHtml("<span class=\"text-gray-600\">No routes registered</span>");
            return;
        }
        StringBuilder html = new StringBuilder("<div class=\"flex flex-wrap gap-2\">");
        for (String r : routes) {
            html.append("<span class=\"bg-gray-800 border border-gray-700 rounded px-2 py-0.5 text-gray-400\">")
                .append(esc(r)).append("</span>");
        }
        html.append("</div>");
        res.sendHtml(html.toString());
    }

    // ── JVM data collection (duplicated from AdminPlugin for self-containment) ──

    private Map<String, Object> collectJvmData() {
        Map<String, Object> data = new LinkedHashMap<>();

        MemoryUsage heap    = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        Map<String, Object> heapMap = new LinkedHashMap<>();
        heapMap.put("usedBytes", heap.getUsed());
        heapMap.put("maxBytes",  heap.getMax());
        heapMap.put("usedMb",    heap.getUsed() >> 20);
        heapMap.put("maxMb",     heap.getMax()  >> 20);
        heapMap.put("pct",       heap.getMax() > 0 ? (int)(heap.getUsed() * 100L / heap.getMax()) : 0);
        data.put("heap", heapMap);

        Map<String, Object> nonHeapMap = new LinkedHashMap<>();
        nonHeapMap.put("usedMb",  nonHeap.getUsed() >> 20);
        nonHeapMap.put("usedBytes", nonHeap.getUsed());
        data.put("nonHeap", nonHeapMap);

        data.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(gc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",            gc.getName());
                    m.put("collectionCount", gc.getCollectionCount());
                    m.put("collectionTimeMs",gc.getCollectionTime());
                    return m;
                }).collect(Collectors.toList()));

        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        data.put("threads", Map.of(
                "live",    tb.getThreadCount(),
                "peak",    tb.getPeakThreadCount(),
                "daemon",  tb.getDaemonThreadCount(),
                "started", tb.getTotalStartedThreadCount()));

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> cpuMap = new LinkedHashMap<>();
        cpuMap.put("processors", os.getAvailableProcessors());
        cpuMap.put("loadAvg",    Math.max(0, os.getSystemLoadAverage()));
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double procCpu = sunOs.getProcessCpuLoad();
            double sysCpu  = sunOs.getCpuLoad();
            cpuMap.put("processCpuPct", procCpu >= 0 ? Math.round(procCpu * 1000) / 10.0 : -1);
            cpuMap.put("systemCpuPct",  sysCpu  >= 0 ? Math.round(sysCpu  * 1000) / 10.0 : -1);
        }
        data.put("cpu", cpuMap);

        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        data.put("runtime", Map.of(
                "jvmName",    rt.getVmName(),
                "jvmVersion", rt.getSpecVersion(),
                "uptimeMs",   rt.getUptime()));

        return data;
    }

    private List<Map<String, Object>> collectQueueStats(VPersistenceService db) {
        Map<String, Map<String, Object>> byQueue = new LinkedHashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT queue, state, COUNT(*) AS cnt FROM vatn_named_queue_jobs " +
                "GROUP BY queue, state ORDER BY queue, state")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String qName = rs.getString("queue");
                    String state = rs.getString("state");
                    long   cnt   = rs.getLong("cnt");
                    Map<String, Object> row = byQueue.computeIfAbsent(qName, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name",    k);
                        m.put("pending", 0L);
                        m.put("claimed", 0L);
                        m.put("done",    0L);
                        m.put("dead",    0L);
                        return m;
                    });
                    switch (state) {
                        case "PENDING" -> row.put("pending", cnt);
                        case "CLAIMED" -> row.put("claimed", cnt);
                        case "DONE"    -> row.put("done",    cnt);
                        case "DEAD"    -> row.put("dead",    cnt);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("collectQueueStats failed: {}", e.getMessage());
        }
        return new ArrayList<>(byQueue.values());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean authorized(VHttpRequest req, VHttpResponse res) {
        if (!config.isAuthEnabled()) return true;
        // Accept if AuthFilter already validated (JWT from /auth/login)
        if (req.getAttribute("vatn.auth", Object.class).isPresent()) return true;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            if (config.getToken().equals(header.substring(7).trim())) return true;
        }
        res.status(401)
           .header("WWW-Authenticate", "Bearer realm=\"vatn-admin\"")
           .header("Content-Type", "text/html;charset=UTF-8")
           .send("<span class=\"text-red-400\">Unauthorized</span>");
        return false;
    }

    // ── HTML building helpers ─────────────────────────────────────────────────

    private static String div(String cls, String content) {
        return "<div class=\"" + cls + "\">" + content + "</div>";
    }

    private static String kv(String label, String value) {
        return "<div class=\"flex justify-between gap-2 py-0.5\">" +
            "<span class=\"text-gray-500\">" + esc(label) + "</span>" +
            "<span class=\"text-gray-200 font-medium truncate\">" + value + "</span></div>";
    }

    private static String badge(String text, String cls) {
        return "<span class=\"px-1.5 py-0.5 rounded text-xs font-medium " + cls + "\">" + esc(text) + "</span>";
    }

    private static String pluginStateBadge(String state) {
        String cls = switch (state) {
            case "RUNNING"    -> "bg-green-500/20 text-green-400 border border-green-500/30";
            case "RESTARTING" -> "bg-yellow-500/20 text-yellow-400 border border-yellow-500/30";
            case "STOPPED"    -> "bg-gray-700 text-gray-400 border border-gray-600";
            case "ERROR"      -> "bg-red-500/20 text-red-400 border border-red-500/30";
            default           -> "bg-gray-700 text-gray-400";
        };
        return badge(state, cls);
    }

    private static String workflowStateBadge(String state) {
        String cls = switch (state) {
            case "SUCCESS"  -> "bg-green-500/20 text-green-400";
            case "FAILED"   -> "bg-red-500/20 text-red-400";
            case "RUNNING"  -> "bg-blue-500/20 text-blue-300";
            case "QUEUED"   -> "bg-gray-700 text-gray-400";
            case "CANCELED" -> "bg-gray-700 text-gray-500";
            default         -> "bg-gray-700 text-gray-400";
        };
        return badge(state, cls);
    }

    private static String workloadStatusBadge(String status) {
        String cls = switch (status) {
            case "RUNNING"  -> "bg-green-500/20 text-green-400";
            case "STARTING" -> "bg-blue-500/20 text-blue-300";
            case "PAUSED"   -> "bg-yellow-500/20 text-yellow-300";
            case "STOPPING" -> "bg-orange-500/20 text-orange-300";
            case "STOPPED"  -> "bg-gray-700 text-gray-400";
            case "FAILED"   -> "bg-red-500/20 text-red-400";
            default         -> "bg-gray-700 text-gray-400";
        };
        return badge(status, cls);
    }

    private static String progressBar(int pct) {
        return progressBar(pct, null);
    }

    private static String progressBar(int pct, String colorClass) {
        int safe = Math.min(100, Math.max(0, pct));
        String col = colorClass != null ? colorClass :
            (safe > 85 ? "bg-red-500" : safe > 65 ? "bg-yellow-500" : "bg-blue-500");
        return "<div class=\"w-full bg-gray-800 rounded-full h-1.5 mt-1\">" +
            "<div class=\"" + col + " h-1.5 rounded-full transition-all\" style=\"width:" + safe + "%\"></div></div>";
    }

    private static String qbadge(String label, long count, String cls) {
        return "<span class=\"inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs " + cls + "\">" +
            esc(label) + " <strong>" + count + "</strong></span>";
    }

    private static String formatUptime(long ms) {
        long s = ms / 1000;
        if (s < 60)  return s + "s";
        long m = s / 60; s %= 60;
        if (m < 60)  return m + "m " + s + "s";
        long h = m / 60; m %= 60;
        if (h < 24)  return h + "h " + m + "m";
        long d = h / 24; h %= 24;
        return d + "d " + h + "h";
    }

    private static String formatResources(Map<String, String> res) {
        if (res == null || res.isEmpty()) return "&#8212;";
        return res.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escJS(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"");
    }

    private static String sanitizeHtmlAttr(String s) {
        if (s == null) return "";
        return s.replaceAll("[^a-zA-Z0-9._:-]", "_");
    }
}
