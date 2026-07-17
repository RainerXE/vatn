package dev.vatn.plugins.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Executes a {@link PynokioScript} step by step.
 *
 * <p>Supported methods:
 * <ul>
 *   <li>{@code shell.run} — run a shell command, optionally in a venv, optionally as a daemon</li>
 *   <li>{@code fs.write}  — write a file (scoped to app root)</li>
 *   <li>{@code fs.read}   — read a file, store result in locals</li>
 *   <li>{@code local.set} — set a local variable</li>
 *   <li>{@code local.get} — get a local variable (use {@code id} to store result)</li>
 *   <li>{@code script.return} — stop execution</li>
 * </ul>
 */
public class PynokioScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(PynokioScriptRunner.class);

    private final PythonRuntime       runtime;
    private final PythonProcessManager manager;
    private final String              appId;
    private final Path                appRoot;     // all paths resolved relative to this

    public PynokioScriptRunner(PythonRuntime runtime, PythonProcessManager manager,
                               String appId, Path appRoot) {
        this.runtime  = runtime;
        this.manager  = manager;
        this.appId    = appId;
        this.appRoot  = appRoot;
    }

    /**
     * Runs all steps of the script. Returns a summary map:
     * {@code {status, stepsRun, daemonIds}}.
     */
    public Map<String, Object> run(PynokioScript script) throws Exception {
        var errors = script.validate();
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid script: " + errors);

        Map<String, Object> locals   = new HashMap<>();
        List<String>        daemons  = new ArrayList<>();
        int                 step     = 0;

        for (PynokioScript.Step s : script.run()) {
            log.info("[PYNOKIO:{}] step[{}] method={}", appId, step, s.method());
            switch (s.method()) {
                case "shell.run"   -> handleShellRun(s, locals, daemons);
                case "fs.write"    -> handleFsWrite(s, locals);
                case "fs.read"     -> handleFsRead(s, locals);
                case "local.set"   -> handleLocalSet(s, locals);
                case "local.get"   -> handleLocalGet(s, locals);
                case "script.return" -> {
                    log.info("[PYNOKIO:{}] script.return — stopping at step {}", appId, step);
                    return result("stopped_early", step + 1, daemons);
                }
                default -> log.warn("[PYNOKIO:{}] Unknown method '{}' — skipping", appId, s.method());
            }
            step++;
        }

        return result("completed", step, daemons);
    }

    // ── Method handlers ───────────────────────────────────────────────────────

    private void handleShellRun(PynokioScript.Step s, Map<String, Object> locals,
                                 List<String> daemons) throws Exception {
        String message     = resolve(s.str("message"), locals);
        String pathParam   = s.str("path");
        String venvName    = s.str("venv");
        String condaEnv    = s.str("conda");
        boolean daemon     = s.bool("daemon");
        boolean autoRestart = s.bool("autoRestart");
        Map<String, String> extraEnv = s.envMap();

        Path workDir = pathParam != null
            ? appRoot.resolve(pathParam).normalize()
            : appRoot;

        // Build the command
        List<String> cmd;
        if (venvName != null) {
            // Ensure venv exists
            if (!runtime.envNames().contains(venvName)) {
                runtime.createEnv(venvName);
            }
            Path venvPython = runtime.venvPython(venvName);
            cmd = buildCommand(message, venvPython.toString());
        } else if (condaEnv != null && runtime.condaAvailable()) {
            cmd = List.of("conda", "run", "-n", condaEnv, "bash", "-c", message);
        } else {
            cmd = List.of("bash", "-c", message);
        }

        Map<String, String> env = runtime.filteredEnv(extraEnv);

        if (daemon) {
            String procId = appId + "-" + UUID.randomUUID().toString().substring(0, 8);
            PythonProcess proc = manager.register(procId, appId, cmd, env, workDir, autoRestart);
            proc.start();
            daemons.add(procId);
            log.info("[PYNOKIO:{}] Daemon started: {}", appId, procId);
        } else {
            // Blocking execution — env must be set on ProcessBuilder, not Process
            ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
            pb.environment().clear();
            pb.environment().putAll(env);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            log.info("[PYNOKIO:{}] shell.run exit={} output={}...", appId, code,
                output.substring(0, Math.min(80, output.length())));
            if (s.id() != null) locals.put(s.id(), output);
            if (code != 0) throw new IOException("shell.run failed (exit " + code + "): " + output);
        }
    }

    private void handleFsWrite(PynokioScript.Step s, Map<String, Object> locals) throws IOException {
        String pathParam = resolve(s.str("path"), locals);
        Object data      = s.params() != null ? s.params().get("data") : null;
        if (pathParam == null) throw new IllegalArgumentException("fs.write: 'path' is required");

        Path target = appRoot.resolve(pathParam).normalize();
        // Safety: must stay within appRoot
        if (!target.startsWith(appRoot)) throw new SecurityException("fs.write path escapes app root: " + target);

        Files.createDirectories(target.getParent());
        String content = data instanceof String str ? str
            : new com.fasterxml.jackson.databind.ObjectMapper()
                  .writerWithDefaultPrettyPrinter().writeValueAsString(data);
        Files.writeString(target, content, StandardCharsets.UTF_8);
        log.debug("[PYNOKIO:{}] fs.write → {}", appId, target);
    }

    private void handleFsRead(PynokioScript.Step s, Map<String, Object> locals) throws IOException {
        String pathParam = resolve(s.str("path"), locals);
        if (pathParam == null) throw new IllegalArgumentException("fs.read: 'path' is required");
        Path target = appRoot.resolve(pathParam).normalize();
        if (!target.startsWith(appRoot)) throw new SecurityException("fs.read path escapes app root");
        String content = Files.readString(target, StandardCharsets.UTF_8);
        if (s.id() != null) locals.put(s.id(), content);
    }

    private void handleLocalSet(PynokioScript.Step s, Map<String, Object> locals) {
        String key = s.str("key");
        Object val = s.params() != null ? s.params().get("value") : null;
        if (key != null) locals.put(key, val);
    }

    private void handleLocalGet(PynokioScript.Step s, Map<String, Object> locals) {
        String key = s.str("key");
        if (key != null && s.id() != null) locals.put(s.id(), locals.get(key));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a command that uses the venv's python to run the message. */
    private static List<String> buildCommand(String message, String pythonBin) {
        // If message starts with "python " or "python3 ", replace the interpreter
        String cmd = message.replaceFirst("^python3?\\s+", pythonBin + " ");
        return List.of("bash", "-c", cmd);
    }

    /** Resolves {{varName}} template expressions in a string. */
    private static String resolve(String template, Map<String, Object> locals) {
        if (template == null) return null;
        String result = template;
        for (var e : locals.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
        }
        return result;
    }

    private static Map<String, Object> result(String status, int stepsRun, List<String> daemons) {
        return Map.of("status", status, "stepsRun", stepsRun, "daemonProcessIds", daemons);
    }
}
