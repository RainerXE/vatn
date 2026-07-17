package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.JvmInstall;
import dev.vatn.plugins.devenv.model.RuntimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JvmScannerTest {

    @Test
    void parsesReleaseFileKeyValues() {
        var kv = JvmScanner.parseRelease(List.of(
                "JAVA_VERSION=\"21.0.8\"",
                "IMPLEMENTOR=\"Oracle Corporation\"",
                "IMPLEMENTOR_VERSION=\"Oracle GraalVM 21.0.8+12.1\"",
                "OS_ARCH=\"aarch64\""));
        assertEquals("21.0.8", kv.get("JAVA_VERSION"));
        assertEquals("Oracle Corporation", kv.get("IMPLEMENTOR"));
        assertEquals("aarch64", kv.get("OS_ARCH"));
    }

    @Test
    void derivesDistribution() {
        // GraalVM: identified by the GRAALVM_VERSION key (implementor is just "Oracle Corporation")
        assertEquals("GraalVM", JvmScanner.deriveDistribution(Map.of(
                "IMPLEMENTOR", "Oracle Corporation", "GRAALVM_VERSION", "25.0.2")));
        // GraalVM via graal modules even without the key
        assertEquals("GraalVM", JvmScanner.deriveDistribution(Map.of(
                "IMPLEMENTOR", "Oracle Corporation", "MODULES", "java.base org.graalvm.nativeimage")));
        assertEquals("Oracle", JvmScanner.deriveDistribution(Map.of(
                "IMPLEMENTOR", "Oracle Corporation", "JAVA_RUNTIME_VERSION", "21.0.5+9-LTS")));
        assertEquals("Temurin", JvmScanner.deriveDistribution(Map.of("IMPLEMENTOR", "Eclipse Adoptium")));
        assertEquals("Corretto", JvmScanner.deriveDistribution(Map.of("IMPLEMENTOR", "Amazon.com Inc.")));
        assertEquals("Zulu", JvmScanner.deriveDistribution(Map.of("IMPLEMENTOR", "Azul Systems, Inc.")));
    }

    @Test
    void fromReleaseFileBuildsInstall(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"25.0.2\"\nIMPLEMENTOR=\"Oracle Corporation\"\n"
                        + "GRAALVM_VERSION=\"25.0.2\"\nOS_ARCH=\"aarch64\"\n");
        JvmInstall j = JvmScanner.fromReleaseFile(home, RuntimeSource.SDKMAN);
        assertEquals("25.0.2", j.version());
        assertEquals("GraalVM", j.distribution());
        assertEquals("aarch64", j.arch());
        assertEquals(RuntimeSource.SDKMAN, j.source());
    }
}
