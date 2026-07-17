package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.RuntimeInstall;
import dev.vatn.plugins.devenv.model.RuntimeSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enumerates <em>all</em> installed language runtimes/toolchains across every source, with the
 * active one marked — the {@link JvmScanner} pattern generalized to Python, Node, Ruby, Go, and
 * Swift. Filesystem-first; a cheap {@code --version} probe resolves exact versions, falling back
 * to a path-derived token.
 */
public final class LanguageRuntimeScanner {

    private final ScannerUtil util;

    public LanguageRuntimeScanner(ScannerUtil util) {
        this.util = util;
    }

    public List<RuntimeInstall> scan() {
        var out = new ArrayList<RuntimeInstall>();
        out.addAll(scanPython());
        out.addAll(scanNode());
        out.addAll(scanRuby());
        out.addAll(scanGo());
        out.addAll(scanSwift());
        return List.copyOf(out);
    }

    // -- Python ---------------------------------------------------------------------------

    List<RuntimeInstall> scanPython() {
        Map<String, RuntimeInstall> acc = new LinkedHashMap<>();
        String active = activeBin("python3");

        for (Path v : ScannerUtil.subdirs(Path.of("/Library/Frameworks/Python.framework/Versions"))) {
            if (!v.getFileName().toString().equals("Current"))
                addInterp(acc, "python", pythonBin(v), RuntimeSource.PYTHON_ORG, "CPython", active, "--version");
        }
        for (String prefix : List.of("/opt/homebrew", "/usr/local")) {
            for (Path keg : matching(Path.of(prefix, "Cellar"), "python@")) {
                for (Path ver : ScannerUtil.subdirs(keg))
                    addInterp(acc, "python", pythonBin(ver), RuntimeSource.HOMEBREW, "CPython", active, "--version");
            }
        }
        for (Path v : ScannerUtil.subdirs(ScannerUtil.homeDir(".pyenv/versions"))) {
            addInterp(acc, "python", pythonBin(v), RuntimeSource.PYENV,
                    derivePythonDistribution(v.getFileName().toString()), active, "--version");
        }
        for (Path base : condaBases()) {
            String dist = derivePythonDistribution(base.getFileName().toString());
            addInterp(acc, "python", pythonBin(base), RuntimeSource.CONDA, dist, active, "--version");
            for (Path env : ScannerUtil.subdirs(base.resolve("envs")))
                addInterp(acc, "python", pythonBin(env), RuntimeSource.CONDA, dist, active, "--version");
        }
        // system / CLT — dedup the apple stub (/usr/bin/python3) against the CLT it execs
        var seenSystemVer = new java.util.HashSet<String>();
        for (String bin : List.of("/usr/bin/python3",
                "/Library/Developer/CommandLineTools/usr/bin/python3", "/usr/local/bin/python3")) {
            int before = acc.size();
            addInterp(acc, "python", Path.of(bin), RuntimeSource.SYSTEM, "CPython", active, "--version");
            if (acc.size() > before) {
                RuntimeInstall added = lastValue(acc);
                if (!seenSystemVer.add(added.version())) removeKey(acc, canonical(Path.of(bin)));
            }
        }
        return List.copyOf(acc.values());
    }

    // -- Node / Ruby / Go -----------------------------------------------------------------

    List<RuntimeInstall> scanNode() {
        Map<String, RuntimeInstall> acc = new LinkedHashMap<>();
        String active = activeBin("node");
        for (Path v : ScannerUtil.subdirs(ScannerUtil.homeDir(".nvm/versions/node")))
            addInterp(acc, "node", v.resolve("bin/node"), RuntimeSource.NVM, "Node.js", active, "--version");
        for (String prefix : List.of("/opt/homebrew", "/usr/local")) {
            for (Path keg : matching(Path.of(prefix, "Cellar"), "node"))
                for (Path ver : ScannerUtil.subdirs(keg))
                    addInterp(acc, "node", ver.resolve("bin/node"), RuntimeSource.HOMEBREW, "Node.js", active, "--version");
        }
        for (String bin : List.of("/usr/local/bin/node", "/usr/bin/node"))
            addInterp(acc, "node", Path.of(bin), RuntimeSource.SYSTEM, "Node.js", active, "--version");
        return List.copyOf(acc.values());
    }

    List<RuntimeInstall> scanRuby() {
        Map<String, RuntimeInstall> acc = new LinkedHashMap<>();
        String active = activeBin("ruby");
        for (Path v : ScannerUtil.subdirs(ScannerUtil.homeDir(".rbenv/versions")))
            addInterp(acc, "ruby", v.resolve("bin/ruby"), RuntimeSource.RBENV, rubyDist(v.toString()), active, "--version");
        for (String prefix : List.of("/opt/homebrew", "/usr/local")) {
            for (Path keg : matching(Path.of(prefix, "Cellar"), "ruby"))
                for (Path ver : ScannerUtil.subdirs(keg))
                    addInterp(acc, "ruby", ver.resolve("bin/ruby"), RuntimeSource.HOMEBREW, "CRuby", active, "--version");
        }
        addInterp(acc, "ruby", Path.of("/usr/bin/ruby"), RuntimeSource.SYSTEM, "CRuby", active, "--version");
        return List.copyOf(acc.values());
    }

    List<RuntimeInstall> scanGo() {
        Map<String, RuntimeInstall> acc = new LinkedHashMap<>();
        String active = activeBin("go");
        for (String prefix : List.of("/opt/homebrew", "/usr/local")) {
            for (Path keg : matching(Path.of(prefix, "Cellar"), "go")) {
                if (!keg.getFileName().toString().equals("go")) continue; // skip gobject-introspection etc.
                for (Path ver : ScannerUtil.subdirs(keg)) {
                    Path bin = ScannerUtil.isFile(ver.resolve("bin/go"))
                            ? ver.resolve("bin/go") : ver.resolve("libexec/bin/go");
                    addInterp(acc, "go", bin, RuntimeSource.HOMEBREW, "Go", active, "version");
                }
            }
        }
        // official go.dev install + go-managed SDKs + system
        addInterp(acc, "go", Path.of("/usr/local/go/bin/go"), RuntimeSource.SYSTEM, "Go", active, "version");
        for (Path sdk : matching(ScannerUtil.homeDir("sdk"), "go"))
            addInterp(acc, "go", sdk.resolve("bin/go"), RuntimeSource.SYSTEM, "Go", active, "version");
        addInterp(acc, "go", Path.of("/usr/bin/go"), RuntimeSource.SYSTEM, "Go", active, "version");
        return List.copyOf(acc.values());
    }

    // -- Swift ----------------------------------------------------------------------------

    private static final Pattern SWIFT_VER = Pattern.compile("Swift version (\\d+(?:\\.\\d+)*)");
    private static final Pattern TOOLCHAIN_VER = Pattern.compile("swift-(\\d+(?:\\.\\d+)*)");

    List<RuntimeInstall> scanSwift() {
        Map<String, RuntimeInstall> acc = new LinkedHashMap<>();
        for (Path tc : matching(Path.of("/Library/Developer/Toolchains"), "swift-")) {
            acc.put(canonical(tc), new RuntimeInstall("swift", swiftToolchainVersion(tc.getFileName().toString()),
                    "swift.org", RuntimeSource.XCODE, tc.toString(), false));
        }
        util.which("swift").ifPresent(path -> {
            String version = util.exec("swift", "--version").map(LanguageRuntimeScanner::parseSwiftVersion)
                    .filter(s -> !s.isBlank()).orElse("");
            boolean fromToolchain = canonical(Path.of(path)).contains("/Toolchains/");
            acc.put("active:" + path, new RuntimeInstall("swift", version,
                    fromToolchain ? "swift.org" : "Apple", RuntimeSource.XCODE, path, true));
        });
        return List.copyOf(acc.values());
    }

    static String parseSwiftVersion(String out) {
        Matcher m = SWIFT_VER.matcher(out == null ? "" : out);
        return m.find() ? m.group(1) : "";
    }

    static String swiftToolchainVersion(String dirName) {
        Matcher m = TOOLCHAIN_VER.matcher(dirName);
        return m.find() ? m.group(1) : "";
    }

    // -- shared ---------------------------------------------------------------------------

    private void addInterp(Map<String, RuntimeInstall> acc, String lang, Path bin,
                           RuntimeSource src, String dist, String activeBin, String versionFlag) {
        if (bin == null || !ScannerUtil.isFile(bin)) return;
        String key = canonical(bin);
        if (acc.containsKey(key)) return;
        String version = util.exec(bin.toString(), versionFlag)
                .map(ScannerUtil::extractVersion).filter(s -> !s.isBlank())
                .orElseGet(() -> pathVersion(bin.toString()));
        boolean active = activeBin != null && activeBin.equals(key);
        acc.put(key, new RuntimeInstall(lang, version, dist, src, bin.toString(), active));
    }

    private String activeBin(String name) {
        return util.which(name).map(Path::of).map(LanguageRuntimeScanner::canonical).orElse(null);
    }

    /** First {@code python3}/{@code python}/{@code python3.x} executable in {@code <prefix>/bin}. */
    static Path pythonBin(Path prefix) {
        Path binDir = prefix.resolve("bin");
        for (String n : List.of("python3", "python")) {
            if (ScannerUtil.isFile(binDir.resolve(n))) return binDir.resolve(n);
        }
        try (Stream<Path> s = Files.list(binDir)) {
            return s.filter(p -> p.getFileName().toString().matches("python3\\.\\d+")).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static java.util.List<Path> condaBases() {
        var out = new ArrayList<Path>();
        for (String b : List.of("miniconda3", "anaconda3", "miniforge3")) {
            Path p = ScannerUtil.homeDir(b);
            if (ScannerUtil.isDir(p)) out.add(p);
        }
        for (String b : List.of("/opt/anaconda3", "/opt/miniconda3")) {
            if (ScannerUtil.isDir(Path.of(b))) out.add(Path.of(b));
        }
        for (Path v : ScannerUtil.subdirs(Path.of("/opt/homebrew/Caskroom/miniconda"))) {
            Path base = v.resolve("base");
            if (ScannerUtil.isDir(base)) out.add(base);
        }
        return out;
    }

    static String derivePythonDistribution(String hint) {
        String h = hint.toLowerCase();
        if (h.contains("pypy")) return "PyPy";
        if (h.contains("graalpy") || h.contains("graalpython")) return "GraalPy";
        if (h.contains("anaconda")) return "Anaconda";
        if (h.contains("miniforge")) return "Miniforge";
        if (h.contains("miniconda") || h.contains("conda")) return "Miniconda";
        return "CPython";
    }

    static String rubyDist(String hint) {
        String h = hint.toLowerCase();
        if (h.contains("jruby")) return "JRuby";
        if (h.contains("truffleruby")) return "TruffleRuby";
        return "CRuby";
    }

    private static final Pattern PATH_VER = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    /** Version-like token from a path (fallback when a probe fails); "" if none. */
    static String pathVersion(String path) {
        Matcher m = PATH_VER.matcher(path);
        return m.find() ? m.group(1) : "";
    }

    private static List<Path> matching(Path parent, String prefix) {
        return ScannerUtil.subdirs(parent).stream()
                .filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
    }

    private static RuntimeInstall lastValue(Map<String, RuntimeInstall> m) {
        RuntimeInstall last = null;
        for (RuntimeInstall v : m.values()) last = v;
        return last;
    }

    private static void removeKey(Map<String, RuntimeInstall> m, String key) {
        m.remove(key);
    }

    private static String canonical(Path p) {
        try { return p.toRealPath().toString(); }
        catch (Exception e) { return p.toAbsolutePath().normalize().toString(); }
    }
}
