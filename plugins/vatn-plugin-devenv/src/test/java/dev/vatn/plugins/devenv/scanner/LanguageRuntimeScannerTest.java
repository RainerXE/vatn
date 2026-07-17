package dev.vatn.plugins.devenv.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanguageRuntimeScannerTest {

    @Test
    void derivesPythonDistribution() {
        assertEquals("CPython", LanguageRuntimeScanner.derivePythonDistribution("3.12.2"));
        assertEquals("PyPy", LanguageRuntimeScanner.derivePythonDistribution("pypy3.10-7.3.15"));
        assertEquals("Anaconda", LanguageRuntimeScanner.derivePythonDistribution("anaconda3-2024.02"));
        assertEquals("Miniconda", LanguageRuntimeScanner.derivePythonDistribution("miniconda3-latest"));
        assertEquals("Miniforge", LanguageRuntimeScanner.derivePythonDistribution("miniforge3"));
        assertEquals("GraalPy", LanguageRuntimeScanner.derivePythonDistribution("graalpy-23.1.0"));
    }

    @Test
    void derivesRubyDistribution() {
        assertEquals("CRuby", LanguageRuntimeScanner.rubyDist("/Users/x/.rbenv/versions/3.3.0"));
        assertEquals("JRuby", LanguageRuntimeScanner.rubyDist("/Users/x/.rbenv/versions/jruby-9.4.5.0"));
        assertEquals("TruffleRuby", LanguageRuntimeScanner.rubyDist("truffleruby-24.0.0"));
    }

    @Test
    void parsesSwiftVersion() {
        assertEquals("6.2.4", LanguageRuntimeScanner.parseSwiftVersion(
                "swift-driver version: 1.127.15 Apple Swift version 6.2.4 (swiftlang-6.2.4.1.4 clang-1700.6.4.2)"));
        assertEquals("5.10", LanguageRuntimeScanner.parseSwiftVersion("Swift version 5.10 (swift-5.10-RELEASE)"));
        assertEquals("", LanguageRuntimeScanner.parseSwiftVersion("no version here"));
    }

    @Test
    void parsesSwiftToolchainDirVersion() {
        assertEquals("5.10.1", LanguageRuntimeScanner.swiftToolchainVersion("swift-5.10.1-RELEASE.xctoolchain"));
        assertEquals("6.0", LanguageRuntimeScanner.swiftToolchainVersion("swift-6.0-DEVELOPMENT-SNAPSHOT.xctoolchain"));
    }

    @Test
    void findsPythonBinInPrefix(@TempDir Path prefix) throws Exception {
        Path bin = prefix.resolve("bin");
        Files.createDirectories(bin);
        Files.createFile(bin.resolve("python3.12"));
        assertEquals(bin.resolve("python3.12"), LanguageRuntimeScanner.pythonBin(prefix));

        Files.createFile(bin.resolve("python3")); // preferred name wins
        assertEquals(bin.resolve("python3"), LanguageRuntimeScanner.pythonBin(prefix));
    }

    @Test
    void pythonBinNullWhenAbsent(@TempDir Path prefix) {
        assertNull(LanguageRuntimeScanner.pythonBin(prefix));
    }
}
