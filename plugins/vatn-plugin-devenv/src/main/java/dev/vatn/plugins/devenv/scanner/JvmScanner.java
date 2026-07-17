package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.JvmInstall;
import dev.vatn.plugins.devenv.model.RuntimeSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates <em>all</em> installed Java runtimes from canonical locations and reads each JDK
 * home's {@code release} file for version + distribution — filesystem-only, so it is complete
 * and independent of the active shell environment. The JDK the active {@code java}/JAVA_HOME
 * resolves to is marked {@code active}.
 */
public final class JvmScanner {

    public List<JvmInstall> scan() {
        // canonical home → install (canonical path dedupes sdkman symlinks vs system dirs)
        Map<String, JvmInstall> byPath = new LinkedHashMap<>();

        // macOS system JVMs
        for (Path vm : ScannerUtil.subdirs(Path.of("/Library/Java/JavaVirtualMachines"))) {
            addHome(byPath, vm.resolve("Contents/Home"), RuntimeSource.SYSTEM);
        }
        // Linux system JVMs
        for (Path vm : ScannerUtil.subdirs(Path.of("/usr/lib/jvm"))) {
            addHome(byPath, vm, RuntimeSource.SYSTEM);
        }
        // SDKMAN candidates (each candidate dir is a JDK home)
        for (Path c : ScannerUtil.subdirs(ScannerUtil.homeDir(".sdkman/candidates/java"))) {
            if (c.getFileName().toString().equals("current")) continue; // symlink, deduped anyway
            addHome(byPath, c, RuntimeSource.SDKMAN);
        }
        // Homebrew openjdk kegs
        for (Path c : ScannerUtil.subdirs(Path.of("/opt/homebrew/Cellar/openjdk"))) {
            addHome(byPath, c.resolve("libexec/openjdk.jdk/Contents/Home"), RuntimeSource.HOMEBREW);
        }

        String activeHome = activeJavaHome();
        var out = new ArrayList<JvmInstall>();
        for (JvmInstall j : byPath.values()) {
            boolean active = activeHome != null && canonical(Path.of(j.path())).equals(activeHome);
            out.add(active ? withActive(j) : j);
        }
        return List.copyOf(out);
    }

    private static void addHome(Map<String, JvmInstall> acc, Path candidate, RuntimeSource source) {
        Path home = resolveHome(candidate);
        if (home == null) return;
        String key = canonical(home);
        if (acc.containsKey(key)) return;
        acc.put(key, fromReleaseFile(home, source));
    }

    /** A JDK home is a dir whose {@code release} lives at the top or under {@code Contents/Home}. */
    static Path resolveHome(Path candidate) {
        if (!ScannerUtil.isDir(candidate)) return null;
        if (ScannerUtil.isFile(candidate.resolve("release"))) return candidate;
        Path bundle = candidate.resolve("Contents/Home");
        if (ScannerUtil.isFile(bundle.resolve("release")) || ScannerUtil.isDir(bundle.resolve("bin"))) {
            return bundle;
        }
        return ScannerUtil.isDir(candidate.resolve("bin")) ? candidate : null;
    }

    /** Parse {@code <home>/release}; falls back to a bare entry if the file is absent. */
    static JvmInstall fromReleaseFile(Path home, RuntimeSource source) {
        Map<String, String> kv = parseRelease(ScannerUtil.readLines(home.resolve("release")));
        String version = kv.getOrDefault("JAVA_VERSION", "");
        String implementor = kv.getOrDefault("IMPLEMENTOR", "");
        String arch = kv.getOrDefault("OS_ARCH", "");
        String distribution = deriveDistribution(kv);
        return new JvmInstall(version, distribution, implementor, arch, home.toString(), source, false);
    }

    /** Parse {@code KEY="value"} lines from a JDK release file. */
    static Map<String, String> parseRelease(List<String> lines) {
        var m = new LinkedHashMap<String, String>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                m.put(k, v);
            }
        }
        return m;
    }

    /**
     * Derive a friendly distribution from a JDK's release map. GraalVM is identified by the
     * {@code GRAALVM_VERSION} key (its IMPLEMENTOR is just "Oracle Corporation"), else by graal
     * modules; other vendors by implementor/version strings.
     */
    static String deriveDistribution(Map<String, String> kv) {
        if (kv.containsKey("GRAALVM_VERSION")) return "GraalVM";
        String implementor = kv.getOrDefault("IMPLEMENTOR", "");
        String s = (implementor + " "
                + kv.getOrDefault("IMPLEMENTOR_VERSION", "") + " "
                + kv.getOrDefault("JAVA_RUNTIME_VERSION", "") + " "
                + kv.getOrDefault("MODULES", "")).toLowerCase();
        if (s.contains("org.graalvm") || s.contains("graalvm")) return "GraalVM";
        if (s.contains("temurin") || s.contains("adoptium") || s.contains("adoptopenjdk")) return "Temurin";
        if (s.contains("corretto") || s.contains("amazon")) return "Corretto";
        if (s.contains("zulu") || s.contains("azul")) return "Zulu";
        if (s.contains("microsoft")) return "Microsoft";
        if (s.contains("semeru") || s.contains("ibm")) return "Semeru";
        if (s.contains("liberica") || s.contains("bellsoft")) return "Liberica";
        if (s.contains("oracle")) return "Oracle";
        if (s.contains("openjdk") || s.contains("homebrew")) return "OpenJDK";
        return implementor.isBlank() ? "Unknown" : implementor;
    }

    /** The JDK home the active java resolves to: JAVA_HOME, else first {@code java} on PATH. */
    private static String activeJavaHome() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) return canonical(Path.of(javaHome));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path java = Path.of(dir, "java");
                if (ScannerUtil.isFile(java)) {
                    // <home>/bin/java → home is two levels up
                    Path home = java.getParent() != null ? java.getParent().getParent() : null;
                    return home != null ? canonical(home) : null;
                }
            }
        }
        return null;
    }

    private static String canonical(Path p) {
        try { return p.toRealPath().toString(); }
        catch (Exception e) { return p.toAbsolutePath().normalize().toString(); }
    }

    private static JvmInstall withActive(JvmInstall j) {
        return new JvmInstall(j.version(), j.distribution(), j.vendor(), j.arch(),
                j.path(), j.source(), true);
    }
}
