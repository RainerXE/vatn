package dev.vatn.verify;

import dev.vatn.api.security.VTrustLevel;
import dev.vatn.core.VNodeRunner;
import dev.vatn.core.VRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class PackageLaunchVerifyTest {

    @TempDir
    Path tempDir;

    @Test
    public void testLoadVNodePackage() throws Exception {
        Path vnodeFile = tempDir.resolve("test-plugin.vnode");
        createMockVNode(vnodeFile);

        VNodeRunner runner = VNodeRunner.create(0, tempDir.resolve("plugins"));
        VRegistry registry = runner.getRegistry();

        // Load the .vnode package
        String pluginId = registry.loadVNode(vnodeFile);

        assertNotNull(pluginId, "Plugin ID should not be null after loading .vnode");
        assertEquals("dev.vatn.test.pkg", pluginId);
        
        // BUG/CHECK: Ensure trust level is SANDBOXED because we didn't sign it
        assertEquals(VTrustLevel.SANDBOXED, registry.getTrustLevel(pluginId), 
            "Unsigned package must have SANDBOXED trust level");
            
        // Verify extraction exists in home .vatn (or mocked path)
        Path packageCache = Path.of(System.getProperty("user.home"), ".vatn", "packages", pluginId, "1.0.0");
        assertTrue(Files.exists(packageCache), "Package should be extracted to cache");
        assertTrue(Files.exists(packageCache.resolve("vatn-plugin.json")));
    }

    private void createMockVNode(Path path) throws IOException {
        String manifest = """
            {
              "id": "dev.vatn.test.pkg",
              "name": "Test Package",
              "version": "1.0.0",
              "vatnVersion": ">=1.0.0",
              "execution": {
                "mode": "IN_PROCESS_JVM",
                "transport": "IN_PROCESS",
                "entrypoint": "dev.vatn.test.Main"
              },
              "capabilities": []
            }
            """;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(path.toFile()))) {
            // Add vatn-plugin.json
            ZipEntry entry = new ZipEntry("vatn-plugin.json");
            zos.putNextEntry(entry);
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Add dummy file
            ZipEntry dummy = new ZipEntry("resources/test.txt");
            zos.putNextEntry(dummy);
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
