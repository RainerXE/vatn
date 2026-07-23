package dev.vatn.plugins.python;

import dev.vatn.api.VService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects the system Python installation, manages virtual environments,
 * and provides the resolved binary paths for scripts to use.
 *
 * <p>Registered in the VATN context as a {@link VService}:
 * <pre>{@code
 * PythonRuntime python = ctx.getService(PythonRuntime.class).orElseThrow();
 * python.createEnv("myapp");
 * Path pythonBin = python.venvPython("myapp");
 * }</pre>
 */
public class PythonRuntime implements VService {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntime.class);

    private final PythonConfig config;
    private final Path         envsDir;
    private final Path         appsDir;

    // Detected runtime info
    private String  pythonVersion   = "unknown";
    private String  pythonBinary    = "python3";
    private boolean uvAvailable     = false;
    private String  uvVersion       = null;
    private boolean condaAvailable  = false;

    // venv name → venv root path
    private final Map<String, Path> envs = new ConcurrentHashMap<>();

    public PythonRuntime(PythonConfig config, Path workspacePath) {
        this.config   = config;
        this.envsDir  = config.envsDir()  != null ? config.envsDir()  : workspacePath.resolve(".vatn/python/envs");
        this.appsDir  = config.appsDir()  != null ? config.appsDir()  : workspacePath.resolve(".vatn/python/apps");
    }

    /** Called by PythonPlugin.onInitialize(). */
    public void initialize() throws IOException {
        Files.createDirectories(envsDir);
        Files.createDirectories(appsDir);
        detectPython();
        detectUv();
        detectConda();
        detectAppleSilicon();
        loadExistingEnvs();
    }

    // ── Runtime detection ─────────────────────────────────────────────────────

    private void detectPython() {
        String[] candidates = config.pythonBinary() != null
            ? new String[]{config.pythonBinary()}
            : new String[]{"python3", "python", "python3.13", "python3.12", "python3.11"};

        for (String candidate : candidates) {
            String version = runQuiet(candidate, "--version");
            if (version != null && version.toLowerCase().startsWith("python")) {
                pythonBinary  = candidate;
                pythonVersion = version.trim();
                log.info("[PYTHON] Found: {} → {}", candidate, pythonVersion);
                return;
            }
        }
        log.warn("[PYTHON] No Python interpreter found — plugin will be limited");
    }

    private void detectUv() {
        String out = runQuiet("uv", "--version");
        if (out != null) {
            uvAvailable = true;
            uvVersion   = out.trim();
            log.info("[PYTHON] uv found: {}", uvVersion);
        }
    }

    private void detectConda() {
        String out = runQuiet("conda", "--version");
        condaAvailable = out != null;
        if (condaAvailable) log.info("[PYTHON] conda found: {}", out.trim());
    }

    private void loadExistingEnvs() throws IOException {
        if (!Files.isDirectory(envsDir)) return;
        try (var stream = Files.list(envsDir)) {
            stream.filter(Files::isDirectory).forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.exists(venvPythonPath(p))) {
                    envs.put(name, p);
                    log.debug("[PYTHON] Loaded existing env: {}", name);
                }
            });
        }
        log.info("[PYTHON] {} venv(s) loaded", envs.size());
    }

    // ── Venv management ───────────────────────────────────────────────────────

    /** Creates a new venv. No-op if already exists. */
    public Path createEnv(String name) throws IOException, InterruptedException {
        Path envPath = envsDir.resolve(name);
        if (envs.containsKey(name)) return envPath;

        log.info("[PYTHON] Creating venv '{}' at {}", name, envPath);
        Process p = new ProcessBuilder(pythonBinary, "-m", "venv", envPath.toString())
            .redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        int code = p.waitFor();
        if (code != 0) throw new IOException("venv creation failed (exit " + code + ")");

        envs.put(name, envPath);
        log.info("[PYTHON] Created venv '{}'", name);
        return envPath;
    }

    /** Deletes a venv. */
    public void deleteEnv(String name) throws IOException {
        Path envPath = envsDir.resolve(name);
        if (Files.exists(envPath)) {
            deleteRecursive(envPath);
        }
        envs.remove(name);
        log.info("[PYTHON] Deleted venv '{}'", name);
    }

    /** Returns the Python binary path for a named venv. */
    public Path venvPython(String name) {
        Path envPath = envs.getOrDefault(name, envsDir.resolve(name));
        return venvPythonPath(envPath);
    }

    /** Returns the pip/uv binary to use for a venv. */
    public List<String> pipCommand(String venvName) {
        Path envPath = envs.getOrDefault(venvName, envsDir.resolve(venvName));
        if (config.preferUv() && uvAvailable) {
            // uv pip install with --python pointing to venv
            return List.of("uv", "pip", "install", "--python",
                           venvPythonPath(envPath).toString());
        }
        Path pip = isWindows()
            ? envPath.resolve("Scripts/pip.exe")
            : envPath.resolve("bin/pip");
        return List.of(pip.toString());
    }

    private static Path venvPythonPath(Path envRoot) {
        return isWindows()
            ? envRoot.resolve("Scripts/python.exe")
            : envRoot.resolve("bin/python");
    }

    // ── Env-var filtering ─────────────────────────────────────────────────────

    public Map<String, String> filteredEnv(Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : config.allowedEnvVars()) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
        if (extra != null) env.putAll(extra);
        return env;
    }

    // ── Disk usage ────────────────────────────────────────────────────────────

    public record DiskUsage(long envsBytesUsed, long appsBytesUsed,
                            long freeBytes, long totalBytes) {
        public String envsHuman()  { return human(envsBytesUsed); }
        public String appsHuman()  { return human(appsBytesUsed); }
        public String freeHuman()  { return human(freeBytes); }
        public String totalHuman() { return human(totalBytes); }
        private static String human(long bytes) {
            if (bytes < 1024) return bytes + " B";
            double kb = bytes / 1024.0;
            if (kb < 1024) return String.format("%.1f KB", kb);
            double mb = kb / 1024.0;
            if (mb < 1024) return String.format("%.1f MB", mb);
            return String.format("%.2f GB", mb / 1024.0);
        }
    }

    public DiskUsage getDiskUsage() {
        long envsUsed  = dirSize(envsDir);
        long appsUsed  = dirSize(appsDir);
        long free = 0, total = 0;
        try {
            java.nio.file.FileStore store = java.nio.file.Files.getFileStore(
                envsDir.toAbsolutePath());
            free  = store.getUsableSpace();
            total = store.getTotalSpace();
        } catch (Exception ignored) {}
        return new DiskUsage(envsUsed, appsUsed, free, total);
    }

    private static long dirSize(Path path) {
        if (!Files.exists(path)) return 0;
        try (var s = Files.walk(path)) {
            return s.filter(Files::isRegularFile)
                    .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } })
                    .sum();
        } catch (IOException e) { return 0; }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  pythonVersion()  { return pythonVersion; }
    public String  pythonBinary()   { return pythonBinary; }
    public boolean uvAvailable()    { return uvAvailable; }
    public String  uvVersion()      { return uvVersion; }
    public boolean condaAvailable() { return condaAvailable; }
    public Path    envsDir()        { return envsDir; }
    public Path    appsDir()        { return appsDir; }
    public Set<String> envNames()   { return Collections.unmodifiableSet(envs.keySet()); }

    public boolean isHealthy() { return !pythonVersion.equals("unknown"); }

    // Apple Silicon / MLX detection
    private boolean appleSilicon = false;
    private boolean mlxAvailable = false;
    private String  mlxVersion   = null;

    public boolean isAppleSilicon() { return appleSilicon; }
    public boolean isMlxAvailable() { return mlxAvailable; }
    public String  mlxVersion()     { return mlxVersion; }

    private void detectAppleSilicon() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        appleSilicon = os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm"));
        if (appleSilicon) {
            log.info("[PYTHON] Apple Silicon detected — MLX inference and fine-tuning available");
            detectMlx();
        }
    }

    private void detectMlx() {
        String v = runQuiet(pythonBinary, "-c",
            "import mlx; import mlx_lm; print(mlx_lm.__version__)");
        if (v != null && !v.isBlank()) {
            mlxAvailable = true;
            mlxVersion   = v.trim();
            log.info("[PYTHON] mlx-lm {} found", mlxVersion);
        } else {
            log.info("[PYTHON] mlx-lm not installed — run 'uv pip install mlx-lm' in a venv to enable");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String runQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var s = Files.list(path)) {
                for (Path child : s.toList()) deleteRecursive(child);
            }
        }
        Files.deleteIfExists(path);
    }
}
