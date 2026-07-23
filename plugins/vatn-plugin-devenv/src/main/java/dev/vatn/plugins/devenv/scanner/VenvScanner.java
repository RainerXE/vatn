package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.VenvEntry;
import dev.vatn.plugins.devenv.model.VenvType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers Python virtual environments by walking configured roots for {@code pyvenv.cfg},
 * and conda envs via the conda CLI. Walk is depth-capped and prunes heavy directories.
 */
public final class VenvScanner {

    private static final Set<String> SKIP = Set.of(
            "node_modules", ".git", ".cargo", ".gradle", ".m2", "target", "build", "dist",
            "__pycache__", ".tox", ".nox", "vendor", ".next", ".nuxt", "DerivedData", "Caches");

    private static final int MAX_DEPTH = 6;

    private final ScannerUtil util;

    public VenvScanner(ScannerUtil util) {
        this.util = util;
    }

    /** Walk the default roots (user home) for venvs, then append conda envs. */
    public List<VenvEntry> scan() {
        var out = new ArrayList<>(walk(List.of(ScannerUtil.home()), MAX_DEPTH));
        out.addAll(scanCondaEnvs());
        return List.copyOf(out);
    }

    /** Walk the given roots to {@code maxDepth}, returning a VenvEntry per {@code pyvenv.cfg}. */
    static List<VenvEntry> walk(List<Path> roots, int maxDepth) {
        var out = new ArrayList<VenvEntry>();
        for (Path root : roots) {
            if (!ScannerUtil.isDir(root)) continue;
            try (Stream<Path> s = Files.walk(root, maxDepth)) {
                s.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("pyvenv.cfg"))
                        .filter(VenvScanner::notPruned)
                        .forEach(cfg -> out.add(toEntry(cfg)));
            } catch (IOException | java.io.UncheckedIOException e) {
                // unreadable subtree — skip
            }
        }
        return out;
    }

    private static boolean notPruned(Path p) {
        for (Path part : p) {
            if (SKIP.contains(part.toString())) return false;
        }
        return true;
    }

    private static VenvEntry toEntry(Path cfg) {
        Path dir = cfg.getParent();
        Map<String, String> kv = parsePyvenvCfg(ScannerUtil.readLines(cfg));
        String version = kv.getOrDefault("version", kv.getOrDefault("version_info", ""));
        VenvType type = detectType(kv);
        int count = sitePackagesCount(dir);
        return new VenvEntry(dir.toString(), version, type, count);
    }

    /** Parse {@code key = value} lines from a pyvenv.cfg into a lower-cased-key map. */
    static Map<String, String> parsePyvenvCfg(List<String> lines) {
        var m = new java.util.HashMap<String, String>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                m.put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
            }
        }
        return m;
    }

    /** Infer venv type from pyvenv.cfg keys. */
    static VenvType detectType(Map<String, String> kv) {
        if (kv.containsKey("uv")) return VenvType.UV;
        if (kv.containsKey("virtualenv")) return VenvType.VIRTUALENV;
        if (kv.containsKey("conda") || kv.containsKey("conda-version")) return VenvType.CONDA;
        if (kv.containsKey("home") || kv.containsKey("version")) return VenvType.VENV;
        return VenvType.UNKNOWN;
    }

    private static int sitePackagesCount(Path venvDir) {
        // lib/pythonX.Y/site-packages/* — count without spawning a subprocess
        Path lib = venvDir.resolve("lib");
        for (Path pyDir : ScannerUtil.subdirs(lib)) {
            Path sp = pyDir.resolve("site-packages");
            if (ScannerUtil.isDir(sp)) return ScannerUtil.subdirs(sp).size();
        }
        return -1;
    }

    private List<VenvEntry> scanCondaEnvs() {
        if (util.which("conda").isEmpty()) return List.of();
        return util.exec("conda", "env", "list").map(VenvScanner::parseCondaEnvList).orElse(List.of());
    }

    /** {@code conda env list}: "name    /path" lines (skip comments and the "*" active marker). */
    static List<VenvEntry> parseCondaEnvList(String output) {
        var out = new ArrayList<VenvEntry>();
        for (String line : output.lines().toList()) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] parts = l.replace("*", " ").trim().split("\\s+");
            String path = parts[parts.length - 1];
            if (path.startsWith("/") || path.contains("\\")) {
                out.add(new VenvEntry(path, "", VenvType.CONDA, -1));
            }
        }
        return List.copyOf(out);
    }
}
