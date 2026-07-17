package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.RuntimeEntry;
import dev.vatn.plugins.devenv.model.RuntimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Probes well-known language-runtime and compiler binaries in parallel via virtual threads.
 * Each probe: resolve the path with {@code which}, then run a version command. Binaries not on
 * PATH are silently skipped.
 */
public final class RuntimeScanner {

    private record BinarySpec(String binary, String... versionFlags) {}

    private static final List<BinarySpec> RUNTIMES = List.of(
            new BinarySpec("java", "--version", "-version"),
            new BinarySpec("python3", "--version"),
            new BinarySpec("python", "--version"),
            new BinarySpec("node", "--version"),
            new BinarySpec("deno", "--version"),
            new BinarySpec("bun", "--version"),
            new BinarySpec("ruby", "--version"),
            new BinarySpec("perl", "--version"),
            new BinarySpec("php", "--version"),
            new BinarySpec("go", "version"),
            new BinarySpec("elixir", "--version"),
            new BinarySpec("erl", "--version"),
            new BinarySpec("lua", "-v"),
            new BinarySpec("dotnet", "--version"),
            new BinarySpec("julia", "--version"),
            new BinarySpec("R", "--version"));

    private static final List<BinarySpec> COMPILERS = List.of(
            new BinarySpec("rustc", "--version"),
            new BinarySpec("cargo", "--version"),
            new BinarySpec("clang", "--version"),
            new BinarySpec("gcc", "--version"),
            new BinarySpec("swift", "--version"),
            new BinarySpec("swiftc", "--version"),
            new BinarySpec("kotlinc", "--version"),
            new BinarySpec("scalac", "--version"),
            new BinarySpec("javac", "--version", "-version"),
            new BinarySpec("mvn", "--version"),
            new BinarySpec("gradle", "--version"),
            new BinarySpec("odin", "version"),
            new BinarySpec("zig", "version"),
            new BinarySpec("ghc", "--version"),
            new BinarySpec("ocaml", "--version"),
            new BinarySpec("nim", "--version"),
            new BinarySpec("dart", "--version"),
            new BinarySpec("tsc", "--version"));

    private final ScannerUtil util;

    public RuntimeScanner(ScannerUtil util) {
        this.util = util;
    }

    public List<RuntimeEntry> scanRuntimes()  { return probeAll(RUNTIMES); }
    public List<RuntimeEntry> scanCompilers() { return probeAll(COMPILERS); }

    private List<RuntimeEntry> probeAll(List<BinarySpec> specs) {
        var results = new ArrayList<RuntimeEntry>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<RuntimeEntry>> tasks = specs.stream()
                    .<Callable<RuntimeEntry>>map(spec -> () -> probe(spec))
                    .toList();
            for (Future<RuntimeEntry> f : exec.invokeAll(tasks)) {
                try {
                    RuntimeEntry e = f.get();
                    if (e != null) results.add(e);
                } catch (Exception ignored) {
                    // One probe failing never affects the others.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        results.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(results);
    }

    private RuntimeEntry probe(BinarySpec spec) {
        Optional<String> resolved = util.which(spec.binary());
        if (resolved.isEmpty()) return null; // not on PATH

        String path = resolved.get();
        String rawVersion = "";
        for (String flag : spec.versionFlags()) {
            Optional<String> out = util.exec(spec.binary(), flag);
            if (out.isPresent() && !out.get().isBlank()) {
                rawVersion = out.get();
                break;
            }
        }
        return new RuntimeEntry(spec.binary(), path, rawVersion,
                ScannerUtil.extractVersion(rawVersion), detectSource(path));
    }

    /** Infer install source from the resolved path; more specific paths first. */
    static RuntimeSource detectSource(String path) {
        if (path == null) return RuntimeSource.UNKNOWN;
        if (path.contains(".mise") || path.contains("mise/installs")) return RuntimeSource.MISE;
        if (path.contains(".sdkman")) return RuntimeSource.SDKMAN;
        if (path.contains(".nvm")) return RuntimeSource.NVM;
        if (path.contains(".pyenv")) return RuntimeSource.PYENV;
        if (path.contains(".rbenv")) return RuntimeSource.RBENV;
        if (path.contains(".asdf")) return RuntimeSource.ASDF;
        if (path.contains("/opt/homebrew") || path.contains("/usr/local/Cellar")
                || path.toLowerCase().contains("homebrew")) return RuntimeSource.HOMEBREW;
        if (path.contains("Xcode") || path.contains("CommandLineTools")) return RuntimeSource.XCODE;
        if (path.startsWith("/usr/bin") || path.startsWith("/bin")) return RuntimeSource.SYSTEM;
        return RuntimeSource.PATH;
    }
}
