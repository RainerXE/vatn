package dev.vatn.plugins.devenv.scanner;

import dev.vatn.api.VProcessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared subprocess + version helpers for all scanners.
 *
 * <p>All subprocess access flows through VATN's {@link VProcessService} at
 * {@link VTrustLevel#RESTRICTED} (DCN-DEV-11), so the node owns sandboxing and the per-call
 * timeout. A {@link ProcessBuilder} fallback — confined to this one method, with proper
 * stream draining and a real wall-clock timeout — is used only when no {@code VProcessService}
 * is available (e.g. bare unit tests).
 */
public final class ScannerUtil {

    private static final Logger log = LoggerFactory.getLogger(ScannerUtil.class);

    private final VProcessService proc; // nullable
    private final Duration timeout;

    public ScannerUtil(VProcessService proc, Duration timeout) {
        this.proc = proc;
        this.timeout = timeout;
    }

    /**
     * Run a read-only command and return trimmed stdout. Empty on timeout, non-zero exit
     * with no output, or any error — scanners treat empty as "not found / unavailable".
     */
    public Optional<String> exec(String... command) {
        try {
            if (proc != null) {
                // Trusted, read-only version/capability probe of a fixed binary allow-list.
                // Uses VProcessService.probe() (unsandboxed) because the OS sandbox blocks tools
                // like swift/xcrun and brew that need broad read access (DCN-DEV-11).
                VProcessService.VProcessResult r = proc.probe(List.of(command));
                // Many tools print --version to stderr (java -version, swift, old python); on a
                // successful exit, fall back to stderr when stdout is empty. (On failure, stderr
                // is an error message, not output — keep treating it as "not found".)
                String out = r.stdout();
                if ((out == null || out.isBlank()) && r.exitCode() == 0) out = r.stderr();
                if (r.exitCode() != 0 && (out == null || out.isBlank())) {
                    return Optional.empty();
                }
                return Optional.ofNullable(out).map(String::trim).filter(s -> !s.isBlank());
            }
            return execFallback(command);
        } catch (Exception e) {
            log.trace("exec failed for {}: {}", command.length > 0 ? command[0] : "", e.toString());
            return Optional.empty();
        }
    }

    /** Resolve a binary on PATH via {@code which}/{@code where}. */
    public Optional<String> which(String binary) {
        String cmd = isWindows() ? "where" : "which";
        return exec(cmd, binary).map(String::trim).filter(s -> !s.isBlank())
                // `which` may return multiple lines; take the first.
                .map(s -> s.lines().findFirst().orElse(s).trim());
    }

    // -- ProcessBuilder fallback (only when VProcessService is absent) --------------------

    private Optional<String> execFallback(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Drain on a separate thread so a chatty/hung child can't block the timeout.
            Process p = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try { return new String(p.getInputStream().readAllBytes()); }
                catch (Exception e) { return ""; }
            });
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                output.cancel(true);
                return Optional.empty();
            }
            String out = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 && out.isBlank()) return Optional.empty();
            return Optional.of(out.trim());
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // -- Version parsing ------------------------------------------------------------------

    private static final Pattern SEMVER =
            Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?(?:[._-][a-zA-Z0-9]+)?)");

    /** Extract the first semver-ish token from arbitrary version output. */
    public static String extractVersion(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Matcher m = SEMVER.matcher(raw);
        return m.find() ? m.group(1) : raw.lines().findFirst().orElse(raw).trim();
    }

    // -- Filesystem helpers (no subprocess) -----------------------------------------------

    public static Path home() { return Path.of(System.getProperty("user.home", "")); }

    /** Resolve a path under the user home (e.g. {@code ".sdkman/candidates"}). */
    public static Path homeDir(String relative) { return home().resolve(relative); }

    public static boolean isDir(Path p)  { return p != null && Files.isDirectory(p); }
    public static boolean isFile(Path p) { return p != null && Files.isRegularFile(p); }

    /** Direct subdirectories of {@code parent}, or empty if it cannot be read. */
    public static List<Path> subdirs(Path parent) {
        if (!isDir(parent)) return List.of();
        try (Stream<Path> s = Files.list(parent)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** All lines of a file, or empty if unreadable. */
    public static List<String> readLines(Path file) {
        if (!isFile(file)) return List.of();
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Whole-file contents, or empty Optional if unreadable. */
    public static Optional<String> readString(Path file) {
        if (!isFile(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // -- Platform -------------------------------------------------------------------------

    public static boolean isMacOS()   { return os().contains("mac"); }
    public static boolean isLinux()   { return os().contains("linux"); }
    public static boolean isWindows() { return os().contains("win"); }

    private static String os() { return System.getProperty("os.name", "").toLowerCase(); }
}
