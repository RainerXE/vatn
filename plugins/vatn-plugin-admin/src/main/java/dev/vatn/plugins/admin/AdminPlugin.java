package dev.vatn.plugins.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.*;
import dev.vatn.api.admin.*;
import dev.vatn.api.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Optional admin dashboard plugin for VATN nodes.
 *
 * <p>Registers a web UI at {@code GET {basePath}} and JSON API endpoints
 * under {@code {basePath}/api/}. All requests require a valid bearer token unless
 * {@link AdminConfig#open()} is used.
 *
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new AdminPlugin())                    // token from VATN_ADMIN_TOKEN env
 *     .addPlugin(new AdminPlugin(AdminConfig.open()))  // no auth (trusted network only)
 *     .start();
 * // → http://localhost:8080/vatn/admin
 * }</pre>
 *
 * <h3>API endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/overview}             — node id, uptime, flavor</li>
 *   <li>{@code GET  /api/plugins}              — list with live state</li>
 *   <li>{@code POST /api/plugins/{id}/restart} — restart a plugin</li>
 *   <li>{@code POST /api/plugins/{id}/stop}    — stop a plugin</li>
 *   <li>{@code GET  /api/health}               — health check statuses</li>
 *   <li>{@code GET  /api/agents}               — VAgent roles</li>
 *   <li>{@code GET  /api/workflows}            — recent DAG runs</li>
 *   <li>{@code GET  /api/routes}               — registered HTTP paths</li>
 *   <li>{@code GET  /api/jvm}                  — heap, GC, threads, CPU via ManagementFactory</li>
 * </ul>
 */
public class AdminPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(AdminPlugin.class);

    private final AdminConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long startedAt = System.currentTimeMillis();

    public AdminPlugin() {
        this(AdminConfig.defaults());
    }

    public AdminPlugin(AdminConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.admin"; }
    @Override public String getName()    { return "VATN Admin UI"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        String base = config.getBasePath();

        // ── Registry for plugin nav contributions ────────────────────────────
        var contributionRegistry = new VAdminContributionRegistryImpl();
        ctx.registerService(VAdminContributionRegistry.class, contributionRegistry);

        ctx.register(base, routes -> {

            // ── HTML dashboard ───────────────────────────────────────────────
            routes.get("", (req, res) -> res.sendHtml(AdminHtml.render(base)));

            // ── overview ─────────────────────────────────────────────────────
            routes.get("/api/overview", (req, res) -> {
                if (!authorized(req, res)) return;
                long uptimeMs = System.currentTimeMillis() - startedAt;
                VConfiguration conf = ctx.getConfiguration();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("nodeId",       ctx.getNodeId());
                d.put("vatnVersion",  "1.0");
                d.put("flavor",       conf != null && conf.isAot() ? "AOT (GraalVM)" : "JVM");
                d.put("uptimeMs",     uptimeMs);
                d.put("uptimeHuman",  formatUptime(uptimeMs));
                d.put("pluginCount",  ctx.getService(VPluginRegistry.class)
                                         .map(r -> r.getPlugins().size()).orElse(0));
                d.put("agentCount",   ctx.getAgentInfos().size());
                sendJson(res, d);
            });

            // ── plugins list ─────────────────────────────────────────────────
            routes.get("/api/plugins", (req, res) -> {
                if (!authorized(req, res)) return;
                Optional<VPluginManager> mgrOpt = ctx.getService(VPluginManager.class);
                if (mgrOpt.isPresent()) {
                    sendJson(res, mgrOpt.get().getStatuses().stream()
                            .map(s -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("id",        s.id());
                                m.put("name",      s.name());
                                m.put("version",   s.version());
                                m.put("state",     s.state().name());
                                m.put("lastError", s.lastError());
                                return m;
                            }).collect(Collectors.toList()));
                } else {
                    // Fallback when VPluginManager not yet available (shouldn't happen)
                    sendJson(res, ctx.getService(VPluginRegistry.class)
                            .map(r -> r.getPlugins().stream()
                                    .map(p -> Map.of("id", p.getId(), "name", p.getName(),
                                                     "version", p.getVersion(), "state", "RUNNING"))
                                    .collect(Collectors.toList()))
                            .orElse(List.of()));
                }
            });

            // ── plugin restart ────────────────────────────────────────────────
            routes.post("/api/plugins/{id}/restart", (req, res) -> {
                if (!authorized(req, res)) return;
                String id = req.getPathParam("id");
                Optional<VPluginManager> mgr = ctx.getService(VPluginManager.class);
                if (mgr.isEmpty()) { res.status(503).send("{\"error\":\"VPluginManager not available\"}"); return; }
                boolean ok = mgr.get().restart(id);
                sendJson(res, Map.of("ok", ok, "pluginId", id,
                        "note", "HTTP routes are not re-wired on restart. Service registrations ARE refreshed."));
            });

            // ── plugin stop ───────────────────────────────────────────────────
            routes.post("/api/plugins/{id}/stop", (req, res) -> {
                if (!authorized(req, res)) return;
                String id = req.getPathParam("id");
                // Prevent stopping the admin plugin itself
                if (getId().equals(id)) {
                    res.status(400).send("{\"error\":\"Cannot stop the admin plugin via itself\"}");
                    return;
                }
                Optional<VPluginManager> mgr = ctx.getService(VPluginManager.class);
                if (mgr.isEmpty()) { res.status(503).send("{\"error\":\"VPluginManager not available\"}"); return; }
                boolean ok = mgr.get().stop(id);
                sendJson(res, Map.of("ok", ok, "pluginId", id));
            });

            // ── health ────────────────────────────────────────────────────────
            routes.get("/api/health", (req, res) -> {
                if (!authorized(req, res)) return;
                List<Map<String, Object>> checks = new ArrayList<>();
                ctx.getAgentInfos().forEach(agent -> checks.add(Map.of(
                        "name",   "agent." + agent.id(),
                        "status", agent.role() == VAgentRole.PRIMARY || agent.role() == VAgentRole.TWIN
                                  ? "UP" : "STANDBY",
                        "detail", agent.role().name())));
                sendJson(res, Map.of("checks", checks, "timestamp", Instant.now().toString()));
            });

            // ── agents ────────────────────────────────────────────────────────
            routes.get("/api/agents", (req, res) -> {
                if (!authorized(req, res)) return;
                sendJson(res, ctx.getAgentInfos().stream()
                        .map(a -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("id",          a.id());
                            m.put("channelType", a.channelType());
                            m.put("role",        a.role().name());
                            m.put("strategy",    a.strategy().name());
                            return m;
                        }).collect(Collectors.toList()));
            });

            // ── workflows ─────────────────────────────────────────────────────
            routes.get("/api/workflows", (req, res) -> {
                if (!authorized(req, res)) return;
                List<Map<String, Object>> result = new ArrayList<>();
                Optional<VDagRegistry> regOpt    = ctx.getService(VDagRegistry.class);
                Optional<VDagEngine>   engineOpt = ctx.getService(VDagEngine.class);
                if (regOpt.isPresent() && engineOpt.isPresent()) {
                    VDagEngine engine = engineOpt.get();
                    engine.listActiveRuns().forEach(r -> result.add(runToMap(r)));
                    regOpt.get().listDags().forEach(dag ->
                            engine.getRuns(dag.id(), config.getWorkflowRunLimit()).stream()
                                    .filter(r -> r.state() != VDagRunState.RUNNING
                                              && r.state() != VDagRunState.QUEUED)
                                    .forEach(r -> result.add(runToMap(r))));
                    LinkedHashSet<String> seen = new LinkedHashSet<>();
                    result.removeIf(m -> !seen.add((String) m.get("runId")));
                }
                sendJson(res, result);
            });

            // ── routes ────────────────────────────────────────────────────────
            routes.get("/api/routes", (req, res) -> {
                if (!authorized(req, res)) return;
                sendJson(res, ctx.getRegisteredRoutes());
            });

            // ── JVM / performance data ────────────────────────────────────────
            routes.get("/api/jvm", (req, res) -> {
                if (!authorized(req, res)) return;
                sendJson(res, collectJvmData());
            });

            // ── queues ────────────────────────────────────────────────────────
            routes.get("/api/queues", (req, res) -> {
                if (!authorized(req, res)) return;
                Optional<VPersistenceService> dbOpt = ctx.getService(VPersistenceService.class);
                if (dbOpt.isEmpty() || ctx.getService(VQueueService.class).isEmpty()) {
                    sendJson(res, List.of());
                    return;
                }
                sendJson(res, collectQueueStats(dbOpt.get()));
            });

            // ── Fragment endpoints for HTMX dashboard ────────────────────────
            var fragments = new AdminFragmentHandler(ctx, config, startedAt);
            routes.get("/fragments/overview",  fragments::overview);
            routes.get("/fragments/plugins",   fragments::plugins);
            routes.get("/fragments/health",    fragments::health);
            routes.get("/fragments/workflows", fragments::workflows);
            routes.get("/fragments/workloads", fragments::workloads);
            routes.get("/fragments/jvm",       fragments::jvm);
            routes.get("/fragments/queues",    fragments::queues);
            routes.get("/fragments/routes",    fragments::routes);
        });

        if (config.isAuthEnabled()) {
            log.info("Admin UI available at {} — bearer token auth enabled", base);
        } else {
            log.warn("Admin UI available at {} — NO AUTH (open access)", base);
        }
    }

    @Override public void onShutdown() {}

    // ── Queue stats ───────────────────────────────────────────────────────────

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

    // ── JVM data collection ───────────────────────────────────────────────────

    private Map<String, Object> collectJvmData() {
        Map<String, Object> data = new LinkedHashMap<>();

        // Heap
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

        // GC collectors
        data.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(gc -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",            gc.getName());
                    m.put("collectionCount", gc.getCollectionCount());
                    m.put("collectionTimeMs",gc.getCollectionTime());
                    return m;
                }).collect(Collectors.toList()));

        // Threads
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        data.put("threads", Map.of(
                "live",    tb.getThreadCount(),
                "peak",    tb.getPeakThreadCount(),
                "daemon",  tb.getDaemonThreadCount(),
                "started", tb.getTotalStartedThreadCount()));

        // CPU & OS — use com.sun.management.OperatingSystemMXBean if available
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

        // JVM runtime info
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        data.put("runtime", Map.of(
                "jvmName",    rt.getVmName(),
                "jvmVersion", rt.getSpecVersion(),
                "uptimeMs",   rt.getUptime()));

        return data;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean authorized(VHttpRequest req, VHttpResponse res) {
        if (!config.isAuthEnabled()) return true;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            if (config.getToken().equals(header.substring(7).trim())) return true;
        }
        res.status(401)
           .header("WWW-Authenticate", "Bearer realm=\"vatn-admin\"")
           .header("Content-Type", "application/json")
           .send("{\"error\":\"Unauthorized\"}");
        return false;
    }

    private void sendJson(VHttpResponse res, Object data) {
        try {
            res.header("Content-Type", "application/json; charset=utf-8")
               .send(mapper.writeValueAsString(data));
        } catch (Exception e) {
            res.status(500).send("{\"error\":\"serialization failed\"}");
        }
    }

    static Map<String, Object> runToMap(VDagRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId",       run.runId());
        m.put("dagId",       run.dagId());
        m.put("state",       run.state().name());
        m.put("started",     run.startDate()  != null ? run.startDate().toString()  : null);
        m.put("finished",    run.endDate()    != null ? run.endDate().toString()    : null);
        m.put("durationMs",  run.startDate()  != null && run.endDate() != null
                ? Duration.between(run.startDate(), run.endDate()).toMillis() : null);
        m.put("triggered",   run.externalTrigger() ? "manual" : "scheduled");
        return m;
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
}

final class VAdminContributionRegistryImpl implements VAdminContributionRegistry {
    private final java.util.List<VAdminContribution> contributions = new CopyOnWriteArrayList<>();
    @Override public void register(VAdminContribution c) { contributions.add(c); }
    @Override public java.util.List<VAdminContribution> getContributions() { return java.util.List.copyOf(contributions); }
}
