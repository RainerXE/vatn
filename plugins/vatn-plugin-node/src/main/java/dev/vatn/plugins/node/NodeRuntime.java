package dev.vatn.plugins.node;

import dev.vatn.api.VService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects the system Node.js/npm installation and manages per-app npm directories.
 *
 * <p>Registered in the VATN context as a {@link VService}.
 */
public class NodeRuntime implements VService {

    private static final Logger log = LoggerFactory.getLogger(NodeRuntime.class);

    private final NodeConfig config;
    private final Path       appsDir;

    private String  nodeVersion  = "unknown";
    private String  nodeBinary   = "node";
    private String  npmBinary    = "npm";
    private String  npxBinary    = "npx";
    private String  npmVersion   = "unknown";

    private final Map<String, Path> apps = new ConcurrentHashMap<>();

    public NodeRuntime(NodeConfig config, Path workspacePath) {
        this.config  = config;
        this.appsDir = config.appsDir() != null
            ? config.appsDir()
            : workspacePath.resolve(".vatn/node/apps");
    }

    public void initialize() throws IOException {
        Files.createDirectories(appsDir);
        detectNode();
        detectNpm();
        loadExistingApps();
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    private void detectNode() {
        String[] candidates = config.nodeBinary() != null
            ? new String[]{config.nodeBinary()}
            : new String[]{"node", "nodejs"};

        for (String candidate : candidates) {
            String v = runQuiet(candidate, "--version");
            if (v != null && v.startsWith("v")) {
                nodeBinary   = candidate;
                nodeVersion  = v.trim();
                log.info("[NODE] Found: {} → {}", candidate, nodeVersion);
                return;
            }
        }
        log.warn("[NODE] No Node.js interpreter found");
    }

    private void detectNpm() {
        String[] npmCandidates = config.npmBinary() != null
            ? new String[]{config.npmBinary()} : new String[]{"npm"};
        String[] npxCandidates = config.npxBinary() != null
            ? new String[]{config.npxBinary()} : new String[]{"npx"};

        for (String candidate : npmCandidates) {
            String v = runQuiet(candidate, "--version");
            if (v != null) { npmBinary = candidate; npmVersion = v.trim();
                log.info("[NODE] npm: {} → {}", candidate, npmVersion); break; }
        }
        for (String candidate : npxCandidates) {
            String v = runQuiet(candidate, "--version");
            if (v != null) { npxBinary = candidate;
                log.info("[NODE] npx: {}", candidate); break; }
        }
    }

    private void loadExistingApps() throws IOException {
        if (!Files.isDirectory(appsDir)) return;
        try (var stream = Files.list(appsDir)) {
            stream.filter(Files::isDirectory).forEach(p -> {
                String name = p.getFileName().toString();
                apps.put(name, p);
                log.debug("[NODE] Loaded app dir: {}", name);
            });
        }
        log.info("[NODE] {} app(s) found", apps.size());
    }

    // ── App management ────────────────────────────────────────────────────────

    /** Returns (creating if needed) the directory for a named app. */
    public Path ensureAppDir(String name) throws IOException {
        Path appDir = appsDir.resolve(name);
        Files.createDirectories(appDir);
        apps.put(name, appDir);
        return appDir;
    }

    public Set<String> appNames() { return Collections.unmodifiableSet(apps.keySet()); }
    public Optional<Path> appDir(String name) { return Optional.ofNullable(apps.get(name)); }
    public Path appsDir() { return appsDir; }

    // ── Env-var filtering ─────────────────────────────────────────────────────

    public Map<String, String> filteredEnv(Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : config.allowedEnvVars()) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
        if (extra != null) env.putAll(extra);
        // Always set NODE_ENV if not already set
        env.putIfAbsent("NODE_ENV", "production");
        return env;
    }

    // ── Disk usage ────────────────────────────────────────────────────────────

    public record DiskUsage(long appsBytesUsed, long freeBytes, long totalBytes) {
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
        long appsUsed = dirSize(appsDir);
        long free = 0, total = 0;
        try {
            java.nio.file.FileStore store = java.nio.file.Files.getFileStore(appsDir.toAbsolutePath());
            free  = store.getUsableSpace();
            total = store.getTotalSpace();
        } catch (Exception ignored) {}
        return new DiskUsage(appsUsed, free, total);
    }

    private static long dirSize(Path path) {
        if (!java.nio.file.Files.exists(path)) return 0;
        try (var s = java.nio.file.Files.walk(path)) {
            return s.filter(java.nio.file.Files::isRegularFile)
                    .mapToLong(p -> { try { return java.nio.file.Files.size(p); } catch (Exception e) { return 0; } })
                    .sum();
        } catch (IOException e) { return 0; }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  nodeVersion() { return nodeVersion; }
    public String  nodeBinary()  { return nodeBinary; }
    public String  npmBinary()   { return npmBinary; }
    public String  npxBinary()   { return npxBinary; }
    public String  npmVersion()  { return npmVersion; }
    public boolean isHealthy()   { return !nodeVersion.equals("unknown"); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String runQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Exception e) { return null; }
    }
}
