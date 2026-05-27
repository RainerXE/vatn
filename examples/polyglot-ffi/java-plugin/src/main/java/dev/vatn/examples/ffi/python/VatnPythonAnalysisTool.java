package dev.vatn.examples.ffi.python;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * VatnPythonAnalysisTool  —  Python tool wrapped as a Vatn plugin
 *
 * Bridges to vatn_analysis_tool.py via ProcessBuilder + JSON stdio.
 * No CPython embedding, no Jython, no GraalPy — just a process fork.
 *
 * Why this approach beats the alternatives for Python:
 *   • FFM cannot call Python's C API safely without hand-written glue and
 *     careful GIL management — far more complex than this.
 *   • Jython is Python 2.7, dead for practical purposes.
 *   • GraalPy works but locks you to GraalVM and adds ~200MB.
 *   • ProcessBuilder works with ANY Python environment the user already has,
 *     including conda/venv with their existing ML libraries.
 *
 * Protocol:
 *   stdin  → single JSON line { "action": "...", "params": { ... } }
 *   stdout ← single JSON line { "ok": true, "result": { ... } }
 *                           or { "ok": false, "error": "..." }
 *
 * The Python script is shipped inside the plugin JAR and extracted to a
 * temp directory on first use (see extractScript()).
 */
// @AgentPlugin(id = "python-analysis-tool", version = "1.0.0")
public class VatnPythonAnalysisTool /* implements AgentPlugin */ {

    private static final int    TIMEOUT_SECONDS = 30;
    private static final String PYTHON_CMD      = detectPython();

    private final Path       scriptPath;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param scriptDir  directory where vatn_analysis_tool.py lives
     *                   (plugin deploy dir, or extracted from JAR resources)
     */
    public VatnPythonAnalysisTool(Path scriptDir) {
        this.scriptPath = scriptDir.resolve("vatn_analysis_tool.py");
    }

    // ── AgentPlugin interface ─────────────────────────────────────────────

    // @Override
    public String getId() { return "python-analysis-tool"; }

    // @Override
    public String getDescription() {
        return """
               Text analysis via Python: word_frequency, sentiment, summarise.
               Requires Python 3.10+ on PATH (or set VATN_PYTHON env var).
               Can be extended with any pip-installed library without touching Vatn.
               """;
    }

    /**
     * Execute an action via the Python subprocess.
     *
     * params must contain:
     *   "action" → "word_frequency" | "sentiment" | "summarise"
     *   "params" → nested Map with action-specific keys
     */
    // @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> params) {
        String action       = (String) params.getOrDefault("action", "sentiment");
        Object innerParams  = params.getOrDefault("params", Map.of("text", params.getOrDefault("text", "")));

        Map<String, Object> request = Map.of("action", action, "params", innerParams);

        try {
            String jsonRequest = json.writeValueAsString(request);
            String jsonResponse = callPython(jsonRequest);

            Map<String, Object> response = json.readValue(jsonResponse, Map.class);

            if (Boolean.TRUE.equals(response.get("ok"))) {
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                result.put("action", action);
                result.put("engine", "python");
                return result;
            } else {
                return Map.of("error", response.get("error"), "action", action);
            }

        } catch (Exception e) {
            return Map.of("error", "Python bridge error: " + e.getMessage());
        }
    }

    // ── subprocess bridge ─────────────────────────────────────────────────

    /**
     * Fork a Python process, write jsonRequest to stdin, collect stdout.
     *
     * Using ProcessBuilder.redirectErrorStream(false) so we can log stderr
     * separately from the JSON result on stdout.
     */
    private String callPython(String jsonRequest) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(PYTHON_CMD, scriptPath.toString());
        pb.redirectErrorStream(false);  // keep stderr separate
        pb.environment().put("PYTHONUNBUFFERED", "1"); // ensure immediate flush

        Process process = pb.start();

        // Write request to stdin and close it so Python's stdin.read() unblocks
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(jsonRequest.getBytes(StandardCharsets.UTF_8));
        }

        // Read stdout (the JSON result) fully
        String stdout = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8
        ).strip();

        // Read stderr for diagnostics (logged, not thrown)
        String stderr = new String(
                process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8
        ).strip();

        boolean exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IOException("Python process timed out after " + TIMEOUT_SECONDS + "s");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException(
                    "Python exited with code " + exitCode + ". stderr: " + stderr
            );
        }

        if (stdout.isEmpty()) {
            throw new IOException("Python produced no output. stderr: " + stderr);
        }

        return stdout;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Detect the Python interpreter to use.
     * Priority: VATN_PYTHON env var → python3 → python
     */
    private static String detectPython() {
        String env = System.getenv("VATN_PYTHON");
        if (env != null && !env.isBlank()) return env;
        // Try python3 first (Linux/macOS standard), fall back to python (Windows)
        return isCommandAvailable("python3") ? "python3" : "python";
    }

    private static boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(3, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
