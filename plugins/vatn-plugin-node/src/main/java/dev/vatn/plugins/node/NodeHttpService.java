package dev.vatn.plugins.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST + admin UI for the Node.js plugin, mounted at {@code /node}.
 *
 * <pre>
 * GET  /node/status                    — runtime info (Node version, npm, apps)
 * GET  /node/apps                      — list app directories
 * POST /node/apps/{name}/install       — npm install in app dir (body: optional packages[])
 * POST /node/apps/{name}/run           — execute a vatn-node.json script
 * GET  /node/apps/{name}/processes     — running processes for an app
 * GET  /node/processes                 — all running processes
 * GET  /node/processes/{id}/status     — process status + log tail
 * POST /node/processes/{id}/stop       — stop a process
 * POST /node/processes/{id}/restart    — restart a stopped process
 * GET  /node/ui                        — admin web UI (HTML)
 * </pre>
 */
class NodeHttpService implements VHttpService {

    private static final Logger log = LoggerFactory.getLogger(NodeHttpService.class);

    private final NodeRuntime        runtime;
    private final NodeProcessManager manager;
    private final RuntimeTomlConfig  tomlConfig;
    private final ObjectMapper       mapper  = new ObjectMapper();
    private final String             adminUi;

    NodeHttpService(NodeRuntime runtime, NodeProcessManager manager,
                    RuntimeTomlConfig tomlConfig) {
        this.runtime    = runtime;
        this.manager    = manager;
        this.tomlConfig = tomlConfig;
        this.adminUi    = loadAdminUi();
    }

    @Override
    public void routing(VHttpRoutes routes) {
        routes.get("/status",                    this::handleStatus);
        routes.get("/config",                    this::handleGetConfig);
        routes.put("/config",                    this::handleSaveConfig);
        routes.get("/apps",                      this::handleListApps);
        routes.post("/apps/{name}/install",      this::handleInstall);
        routes.post("/apps/{name}/run",          this::handleRunApp);
        routes.get("/apps/{name}/processes",     this::handleAppProcesses);
        routes.get("/processes",                 this::handleListProcesses);
        routes.get("/processes/{id}/status",     this::handleProcessStatus);
        routes.post("/processes/{id}/stop",      this::handleStopProcess);
        routes.post("/processes/{id}/restart",   this::handleRestartProcess);
        routes.get("/ui",                        this::handleUi);
    }

    private void handleGetConfig(VHttpRequest req, VHttpResponse res) {
        NodeRuntime.DiskUsage disk = runtime.getDiskUsage();
        res.sendJson(toJson(Map.of(
            "appsDir",  runtime.appsDir().toString(),
            "disk", Map.of(
                "appsBytesUsed", disk.appsBytesUsed(),
                "appsHuman",     disk.appsHuman(),
                "freeBytes",     disk.freeBytes(),
                "freeHuman",     disk.freeHuman(),
                "totalBytes",    disk.totalBytes(),
                "totalHuman",    disk.totalHuman()
            ),
            "note", "Path changes are saved to .vatn/vatn.toml and take effect after restart"
        )));
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void handleSaveConfig(VHttpRequest req, VHttpResponse res) {
        try {
            Map body = mapper.readValue(req.getBody(), Map.class);
            java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
            if (body.containsKey("appsDir"))    values.put("apps_dir",    (String) body.get("appsDir"));
            if (body.containsKey("nodeBinary")) values.put("node_binary", (String) body.get("nodeBinary"));
            if (body.containsKey("npmBinary"))  values.put("npm_binary",  (String) body.get("npmBinary"));
            tomlConfig.write(values);
            res.sendJson(toJson(Map.of(
                "status", "saved",
                "restartRequired", true,
                "message", "Configuration saved to .vatn/vatn.toml — restart VATN to apply"
            )));
        } catch (Exception e) {
            res.status(500).sendJson("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleStatus(VHttpRequest req, VHttpResponse res) {
        res.sendJson(toJson(Map.of(
            "node",        runtime.nodeBinary(),
            "version",     runtime.nodeVersion(),
            "healthy",     runtime.isHealthy(),
            "npm",         runtime.npmVersion(),
            "npx",         runtime.npxBinary(),
            "appCount",    runtime.appNames().size(),
            "processCount",manager.getAll().size()
        )));
    }

    private void handleListApps(VHttpRequest req, VHttpResponse res) {
        try {
            Path appsDir = runtime.appsDir();
            var apps = new ArrayList<Map<String, Object>>();
            if (Files.isDirectory(appsDir)) {
                try (var stream = Files.list(appsDir)) {
                    stream.filter(Files::isDirectory).forEach(d -> {
                        String name = d.getFileName().toString();
                        boolean hasScript = Files.exists(d.resolve("vatn-node.json"))
                                         || Files.exists(d.resolve("pinokio.json"));
                        boolean hasPackageJson = Files.exists(d.resolve("package.json"));
                        apps.add(Map.of(
                            "name", name, "path", d.toString(),
                            "hasScript", hasScript, "hasPackageJson", hasPackageJson,
                            "runningProcesses", manager.getByApp(name).size()
                        ));
                    });
                }
            }
            res.sendJson(toJson(Map.of("apps", apps, "count", apps.size())));
        } catch (Exception e) { res.status(500).sendJson(err(e)); }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void handleInstall(VHttpRequest req, VHttpResponse res) {
        String name = req.getPathParam("name");
        try {
            Path appDir = runtime.ensureAppDir(name);
            List<String> packages = List.of();
            String body = req.getBody();
            if (body != null && !body.isBlank()) {
                Map m = mapper.readValue(body, Map.class);
                Object p = m.get("packages");
                if (p instanceof List<?> l) packages = (List<String>) l;
            }
            List<String> cmd = new ArrayList<>(List.of(runtime.npmBinary(), "install"));
            cmd.addAll(packages);
            Process p = new ProcessBuilder(cmd).directory(appDir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) { res.status(500).sendJson(toJson(Map.of("error", out))); return; }
            res.sendJson(toJson(Map.of("status", "installed", "app", name, "packages", packages)));
        } catch (Exception e) { res.status(500).sendJson(err(e)); }
    }

    private void handleRunApp(VHttpRequest req, VHttpResponse res) {
        String appName = req.getPathParam("name");
        try {
            Path appDir = runtime.ensureAppDir(appName);
            Path scriptPath = Files.exists(appDir.resolve("vatn-node.json"))
                ? appDir.resolve("vatn-node.json")
                : appDir.resolve("pinokio.json");

            if (!Files.exists(scriptPath)) {
                res.status(404).sendJson("{\"error\":\"No vatn-node.json or pinokio.json in app '" + appName + "'\"}");
                return;
            }
            var script = NodeScriptRunner.NodeScript.fromFile(scriptPath);
            var runner = new NodeScriptRunner(runtime, manager, appName, appDir);
            var result = runner.run(script);
            res.sendJson(toJson(result));
        } catch (Exception e) {
            log.error("[NODE] Failed to run app '{}': {}", appName, e.getMessage(), e);
            res.status(500).sendJson(err(e));
        }
    }

    private void handleAppProcesses(VHttpRequest req, VHttpResponse res) {
        String appName = req.getPathParam("name");
        var procs = manager.getByApp(appName).stream().map(this::processToMap).collect(Collectors.toList());
        res.sendJson(toJson(Map.of("appId", appName, "processes", procs)));
    }

    private void handleListProcesses(VHttpRequest req, VHttpResponse res) {
        var all = manager.getAll().stream().map(this::processToMap).collect(Collectors.toList());
        res.sendJson(toJson(Map.of("processes", all, "count", all.size())));
    }

    private void handleProcessStatus(VHttpRequest req, VHttpResponse res) {
        String id = req.getPathParam("id");
        manager.get(id).ifPresentOrElse(p -> {
            var m = processToMap(p);
            ((Map<String, Object>) m).put("logs", p.getLogTail(50));
            res.sendJson(toJson(m));
        }, () -> res.status(404).sendJson("{\"error\":\"Process not found\"}"));
    }

    private void handleStopProcess(VHttpRequest req, VHttpResponse res) {
        String id = req.getPathParam("id");
        manager.get(id).ifPresentOrElse(p -> {
            p.stop();
            res.sendJson("{\"status\":\"stopping\",\"id\":\"" + id + "\"}");
        }, () -> res.status(404).sendJson("{\"error\":\"Process not found\"}"));
    }

    private void handleRestartProcess(VHttpRequest req, VHttpResponse res) {
        String id = req.getPathParam("id");
        manager.get(id).ifPresentOrElse(p -> {
            p.stop();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            p.start();
            res.sendJson("{\"status\":\"restarting\",\"id\":\"" + id + "\"}");
        }, () -> res.status(404).sendJson("{\"error\":\"Process not found\"}"));
    }

    private void handleUi(VHttpRequest req, VHttpResponse res) {
        res.header("Content-Type", "text/html; charset=UTF-8");
        res.send(adminUi);
    }

    private Map<String, Object> processToMap(NodeProcess p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id",           p.id());
        m.put("appId",        p.appId());
        m.put("status",       p.status().name().toLowerCase());
        m.put("pid",          p.pid());
        m.put("restartCount", p.restartCount());
        m.put("lastExitCode", p.lastExitCode());
        m.put("startedAt",    p.startedAt() != null ? p.startedAt().toString() : null);
        m.put("autoRestart",  p.autoRestart());
        return m;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    private String err(Exception e) {
        return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
    }

    private static String loadAdminUi() {
        try (var is = NodeHttpService.class.getResourceAsStream("/node-admin.html")) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return "<html><body><p>Admin UI not found.</p></body></html>";
    }
}
