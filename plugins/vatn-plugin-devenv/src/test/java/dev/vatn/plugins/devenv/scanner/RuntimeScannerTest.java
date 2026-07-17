package dev.vatn.plugins.devenv.scanner;

import dev.vatn.api.VProcessService;
import dev.vatn.plugins.devenv.model.RuntimeEntry;
import dev.vatn.plugins.devenv.model.RuntimeSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeScannerTest {

    /** A fake VProcessService that maps a command line to a canned result. */
    private static final class FakeProc implements VProcessService {
        private final BiFunction<List<String>, Object, VProcessResult> handler;
        FakeProc(BiFunction<List<String>, Object, VProcessResult> handler) { this.handler = handler; }

        @Override
        public VProcessResult execute(List<String> command, Map<String, String> env, String workingDir) {
            return handler.apply(command, null);
        }
        @Override
        public VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir) {
            throw new UnsupportedOperationException();
        }
    }

    private static RuntimeScanner scanner(BiFunction<List<String>, Object, VProcessService.VProcessResult> h) {
        return new RuntimeScanner(new ScannerUtil(new FakeProc(h), Duration.ofSeconds(5)));
    }

    @Test
    void detectsInstalledRuntimeWithVersionAndSource() {
        RuntimeScanner s = scanner((cmd, ignored) -> {
            if (cmd.equals(List.of("which", "java")))      return ok("/usr/bin/java");
            if (cmd.equals(List.of("java", "--version")))  return ok("openjdk 21.0.5 2024-10-15 LTS");
            return notFound(); // every other `which` fails → binary absent
        });

        List<RuntimeEntry> runtimes = s.scanRuntimes();

        Optional<RuntimeEntry> java = runtimes.stream().filter(r -> r.name().equals("java")).findFirst();
        assertTrue(java.isPresent(), "java should be detected");
        assertEquals("/usr/bin/java", java.get().path());
        assertEquals("21.0.5", java.get().version());
        assertEquals(RuntimeSource.SYSTEM, java.get().source());
        // Nothing else was stubbed as present.
        assertEquals(1, runtimes.size());
    }

    @Test
    void emptyWhenNothingOnPath() {
        RuntimeScanner s = scanner((cmd, ignored) -> notFound());
        assertTrue(s.scanRuntimes().isEmpty());
        assertTrue(s.scanCompilers().isEmpty());
    }

    @Test
    void degradesGracefullyWhenProcessServiceThrows() {
        // Simulates VProcessService timing out / failing on every call (e.g. hung child).
        RuntimeScanner s = scanner((cmd, ignored) -> { throw new RuntimeException("boom"); });
        assertTrue(s.scanRuntimes().isEmpty(), "a failing process service must not throw");
    }

    @Test
    void detectSourceClassifiesByPath() {
        assertEquals(RuntimeSource.HOMEBREW, RuntimeScanner.detectSource("/opt/homebrew/bin/node"));
        assertEquals(RuntimeSource.MISE,     RuntimeScanner.detectSource("/Users/x/.local/share/mise/installs/node/20/bin/node"));
        assertEquals(RuntimeSource.SDKMAN,   RuntimeScanner.detectSource("/Users/x/.sdkman/candidates/java/current/bin/java"));
        assertEquals(RuntimeSource.SYSTEM,   RuntimeScanner.detectSource("/usr/bin/python3"));
        assertEquals(RuntimeSource.PATH,     RuntimeScanner.detectSource("/some/other/bin/tool"));
    }

    @Test
    void extractVersionPullsSemver() {
        assertEquals("21.0.5", ScannerUtil.extractVersion("openjdk 21.0.5 2024-10-15 LTS"));
        assertEquals("1.82.0", ScannerUtil.extractVersion("rustc 1.82.0 (f6e511eec 2024-10-15)"));
        assertEquals("", ScannerUtil.extractVersion(""));
    }

    private static VProcessService.VProcessResult ok(String stdout) {
        return new VProcessService.VProcessResult(0, stdout, "");
    }
    private static VProcessService.VProcessResult notFound() {
        return new VProcessService.VProcessResult(1, "", "not found");
    }
}
