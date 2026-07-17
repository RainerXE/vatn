package dev.vatn.plugins.node;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Executes a {@code run[]} script for Node.js apps.
 *
 * <p>Uses the same JSON format as {@code PynokioScript} for consistency:
 * <pre>{@code
 * {
 *   "run": [
 *     { "method": "shell.run", "params": { "message": "npm install", "path": "./app" } },
 *     { "method": "npm.install", "params": { "packages": ["express", "dotenv"] } },
 *     { "method": "shell.run", "params": { "message": "node server.js",
 *                                          "daemon": true, "autoRestart": true } }
 *   ]
 * }
 * }</pre>
 *
 * <p>Supported methods:
 * <ul>
 *   <li>{@code shell.run}    — run any shell command; daemon mode for servers</li>
 *   <li>{@code npm.install}  — npm install [packages] in app dir</li>
 *   <li>{@code npx.run}      — npx command in app dir</li>
 *   <li>{@code fs.write}     — write file (scoped to app root)</li>
 *   <li>{@code fs.read}      — read file, store in locals</li>
 *   <li>{@code local.set/get}— in-session k/v store</li>
 *   <li>{@code script.return}— stop execution</li>
 * </ul>
 */
public class NodeScriptRunner {

    private static final Logger        log    = LoggerFactory.getLogger(NodeScriptRunner.class);
    private static final ObjectMapper  MAPPER = new ObjectMapper();

    private final NodeRuntime        runtime;
    private final NodeProcessManager manager;
    private final String             appId;
    private final Path               appRoot;

    public NodeScriptRunner(NodeRuntime runtime, NodeProcessManager manager,
                            String appId, Path appRoot) {
        this.runtime  = runtime;
        this.manager  = manager;
        this.appId    = appId;
        this.appRoot  = appRoot;
    }

    // ── Script data model (same shape as PynokioScript) ──────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeScript(
        @JsonProperty("run") List<Step> run
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Step(
            @JsonProperty("method") String method,
            @JsonProperty("params") Map<String, Object> params,
            @JsonProperty("id")     String id
        ) {
            public String str(String key) {
                Object v = params == null ? null : params.get(key);
                return v == null ? null : v.toString();
            }
            public boolean bool(String key) {
                Object v = params == null ? null : params.get(key);
                if (v instanceof Boolean b) return b;
                return "true".equalsIgnoreCase(String.valueOf(v));
            }
            @SuppressWarnings("unchecked")
            public Map<String, String> envMap() {
                Object v = params == null ? null : params.get("env");
                return v instanceof Map<?,?> m ? (Map<String,String>)m : Map.of();
            }
            @SuppressWarnings("unchecked")
            public List<String> packageList() {
                Object v = params == null ? null : params.get("packages");
                return v instanceof List<?> l ? (List<String>)l : List.of();
            }
        }

        public static NodeScript fromFile(Path path) throws IOException {
            return MAPPER.readValue(path.toFile(), NodeScript.class);
        }
        public static NodeScript fromJson(String json) throws IOException {
            return MAPPER.readValue(json, NodeScript.class);
        }
    }

    // ── Runner ────────────────────────────────────────────────────────────────

    public Map<String, Object> run(NodeScript script) throws Exception {
        Map<String, Object> locals  = new HashMap<>();
        List<String>        daemons = new ArrayList<>();
        int step = 0;

        for (NodeScript.Step s : script.run()) {
            log.info("[NODE-SCRIPT:{}] step[{}] method={}", appId, step, s.method());
            switch (s.method()) {
                case "shell.run"    -> handleShellRun(s, locals, daemons);
                case "npm.install"  -> handleNpmInstall(s, locals);
                case "npx.run"      -> handleNpxRun(s, locals);
                case "fs.write"     -> handleFsWrite(s, locals);
                case "fs.read"      -> handleFsRead(s, locals);
                case "local.set"    -> { if (s.str("key") != null)
                                            locals.put(s.str("key"),
                                            s.params() != null ? s.params().get("value") : null); }
                case "local.get"    -> { if (s.str("key") != null && s.id() != null)
                                            locals.put(s.id(), locals.get(s.str("key"))); }
                case "script.return" -> { return result("stopped_early", step + 1, daemons); }
                default -> log.warn("[NODE-SCRIPT:{}] Unknown method '{}' — skipping", appId, s.method());
            }
            step++;
        }
        return result("completed", step, daemons);
    }

    // ── Method handlers ───────────────────────────────────────────────────────

    private void handleShellRun(NodeScript.Step s, Map<String, Object> locals,
                                 List<String> daemons) throws Exception {
        String message     = resolve(s.str("message"), locals);
        String pathParam   = s.str("path");
        boolean daemon     = s.bool("daemon");
        boolean autoRestart = s.bool("autoRestart");

        Path workDir = pathParam != null ? appRoot.resolve(pathParam).normalize() : appRoot;
        List<String> cmd = List.of("bash", "-c",
            message.replace("node ", runtime.nodeBinary() + " ")
                   .replace("npm ",  runtime.npmBinary() + " ")
                   .replace("npx ",  runtime.npxBinary() + " "));
        Map<String, String> env = runtime.filteredEnv(s.envMap());

        if (daemon) {
            String procId = appId + "-" + UUID.randomUUID().toString().substring(0, 8);
            NodeProcess proc = manager.register(procId, appId, cmd, env, workDir, autoRestart);
            proc.start();
            daemons.add(procId);
            log.info("[NODE-SCRIPT:{}] Daemon started: {}", appId, procId);
        } else {
            execBlocking(cmd, env, workDir, s.id(), locals);
        }
    }

    private void handleNpmInstall(NodeScript.Step s, Map<String, Object> locals) throws Exception {
        List<String> packages = s.packageList();
        List<String> cmd = new ArrayList<>();
        cmd.add(runtime.npmBinary());
        cmd.add("install");
        cmd.addAll(packages);
        execBlocking(cmd, runtime.filteredEnv(s.envMap()), appRoot, s.id(), locals);
    }

    private void handleNpxRun(NodeScript.Step s, Map<String, Object> locals) throws Exception {
        String message = resolve(s.str("message"), locals);
        List<String> cmd = List.of("bash", "-c", runtime.npxBinary() + " " + message);
        execBlocking(cmd, runtime.filteredEnv(s.envMap()), appRoot, s.id(), locals);
    }

    private void handleFsWrite(NodeScript.Step s, Map<String, Object> locals) throws IOException {
        String pathParam = resolve(s.str("path"), locals);
        if (pathParam == null) throw new IllegalArgumentException("fs.write: 'path' required");
        Path target = appRoot.resolve(pathParam).normalize();
        if (!target.startsWith(appRoot)) throw new SecurityException("Path escapes app root");
        Files.createDirectories(target.getParent());
        Object data = s.params() != null ? s.params().get("data") : null;
        String content = data instanceof String str ? str
            : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void handleFsRead(NodeScript.Step s, Map<String, Object> locals) throws IOException {
        String pathParam = resolve(s.str("path"), locals);
        if (pathParam == null) throw new IllegalArgumentException("fs.read: 'path' required");
        Path target = appRoot.resolve(pathParam).normalize();
        if (!target.startsWith(appRoot)) throw new SecurityException("Path escapes app root");
        if (s.id() != null) locals.put(s.id(), Files.readString(target, StandardCharsets.UTF_8));
    }

    private void execBlocking(List<String> cmd, Map<String, String> env, Path workDir,
                               String outputId, Map<String, Object> locals) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (outputId != null) locals.put(outputId, output);
        if (code != 0) throw new IOException("Command failed (exit " + code + "): " + output.substring(0, Math.min(200, output.length())));
    }

    private static String resolve(String template, Map<String, Object> locals) {
        if (template == null) return null;
        String s = template;
        for (var e : locals.entrySet()) s = s.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
        return s;
    }

    private static Map<String, Object> result(String status, int steps, List<String> daemons) {
        return Map.of("status", status, "stepsRun", steps, "daemonProcessIds", daemons);
    }
}
