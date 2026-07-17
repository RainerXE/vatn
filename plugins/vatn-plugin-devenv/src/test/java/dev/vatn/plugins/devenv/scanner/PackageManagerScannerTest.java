package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.PackageEntry;
import dev.vatn.plugins.devenv.model.ServiceEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageManagerScannerTest {

    // -- Homebrew Cellar/Caskroom (filesystem) -------------------------------------------

    @Test
    void parsesInstallDirNewestVersion(@TempDir Path cellar) throws Exception {
        Files.createDirectories(cellar.resolve("git/2.44.0"));
        Files.createDirectories(cellar.resolve("openssl@3/3.2.0"));
        Files.createDirectories(cellar.resolve("openssl@3/3.2.1")); // newest sorts last
        List<PackageEntry> p = PackageManagerScanner.parseInstallDir(cellar, "homebrew");
        assertEquals(2, p.size());
        assertEquals("git", p.get(0).name());
        assertEquals("2.44.0", p.get(0).version());
        assertEquals("openssl@3", p.get(1).name());
        assertEquals("3.2.1", p.get(1).version());
        assertEquals("homebrew", p.get(1).source());
    }

    // -- npm globals (filesystem, incl. @scope) ------------------------------------------

    @Test
    void collectsNpmPackagesIncludingScopes(@TempDir Path nm) throws Exception {
        Files.createDirectories(nm.resolve("npm"));
        Files.writeString(nm.resolve("npm/package.json"), "{\n  \"name\": \"npm\",\n  \"version\": \"10.2.3\"\n}");
        Files.createDirectories(nm.resolve("@angular/cli"));
        Files.writeString(nm.resolve("@angular/cli/package.json"), "{ \"version\": \"17.0.0\" }");
        Files.createDirectories(nm.resolve(".bin")); // must be ignored

        List<PackageEntry> p = PackageManagerScanner.collectNpm(nm);
        assertEquals(2, p.size());
        assertTrue(p.stream().anyMatch(e -> e.name().equals("npm") && e.version().equals("10.2.3")));
        assertTrue(p.stream().anyMatch(e -> e.name().equals("@angular/cli") && e.version().equals("17.0.0")));
    }

    @Test
    void readsPackageJsonVersion(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"x\",\"version\":\"1.2.3\",\"bin\":{}}");
        assertEquals("1.2.3", PackageManagerScanner.readPkgVersion(dir));
    }

    // -- best-effort subprocess parsers --------------------------------------------------

    @Test
    void parsesBrewServicesSkippingHeader() {
        List<ServiceEntry> s = PackageManagerScanner.parseBrewServices(
                "Name          Status  User  File\npostgresql@16 started rainer /path\nredis         stopped\n");
        assertEquals(2, s.size());
        assertEquals("postgresql@16", s.get(0).name());
        assertEquals("started", s.get(0).status());
        assertEquals("rainer", s.get(0).user());
        assertEquals("", s.get(1).user());
    }

    @Test
    void parsesPipFreeze() {
        List<PackageEntry> p = PackageManagerScanner.parsePipFreeze(
                "requests==2.31.0\nnumpy==1.26.4\n# comment\nlocalpkg @ file:///tmp/x\n");
        assertEquals(3, p.size());
        assertEquals("requests", p.get(0).name());
        assertEquals("localpkg", p.get(2).name());
        assertEquals("", p.get(2).version());
    }
}
