package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import dev.vatn.core.VRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chaos Monkey for VATN Plugins.
 * Tests how the node handles malicious, malformed, or crashing plugins.
 */
public class PluginChaosMonkeyTest {

    @TempDir
    Path tempDir;

    private VNodeRunner runner;
    private VRegistry registry;

    @BeforeEach
    public void setup() {
        runner = VNodeRunner.create(0, tempDir.resolve("plugins"));
        registry = runner.getRegistry();
    }

    @Test
    public void testMalformedJSONManifest() throws Exception {
        Path vnodeFile = tempDir.resolve("malformed.vnode");
        createZip(vnodeFile, "vatn-plugin.json", "{ \"id\": \"bad-json\", \"name\": ... malformed ... }");

        // Should throw or return null gracefully, but definitely NOT crash the whole JVM
        Exception exception = assertThrows(Exception.class, () -> registry.loadVNode(vnodeFile));
        System.out.println("Caught expected exception for malformed JSON: " + exception.getMessage());
    }

    @Test
    public void testMissingMandatoryFields() throws Exception {
        Path vnodeFile = tempDir.resolve("missing-fields.vnode");
        createZip(vnodeFile, "vatn-plugin.json", "{ \"id\": \"missing-fields\" }");

        // Jackson should fail to parse into VPluginManifest due to @JsonIgnoreProperties(ignoreUnknown = true)
        // BUT we have required fields in the schema. In current Java Impl, it might just have nulls.
        // We should ensure the node detects these nulls and rejects.
        Exception exception = assertThrows(Exception.class, () -> {
             String id = registry.loadVNode(vnodeFile);
             if (id == null) throw new IOException("Rejected");
        });
        assertNotNull(exception);
    }

    @Test
    public void testPluginWithMissingManifest() throws Exception {
        Path vnodeFile = tempDir.resolve("no-manifest.vnode");
        createZip(vnodeFile, "something-else.json", "{}");

        IOException exception = assertThrows(IOException.class, () -> registry.loadVNode(vnodeFile));
        assertTrue(exception.getMessage().contains("Missing vatn-plugin.json"));
    }

    @Test
    public void testPathTraversalInPackage() throws Exception {
        Path vnodeFile = tempDir.resolve("traversal.vnode");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(vnodeFile.toFile()))) {
            // Normal manifest
            zos.putNextEntry(new ZipEntry("vatn-plugin.json"));
            zos.write("{ \"id\": \"traversal\", \"name\": \"T\", \"version\": \"1\", \"vatnVersion\": \"1\", \"execution\": {\"mode\":\"EMBEDDED\", \"transport\":\"IN_PROCESS\", \"entrypoint\":\"E\"}, \"capabilities\": [] }".getBytes());
            zos.closeEntry();

            // Malicious entry attempting to write outside cache
            zos.putNextEntry(new ZipEntry("../../../malicious.txt"));
            zos.write("evil".getBytes());
            zos.closeEntry();
        }

        // VPackageService should catch this in extractPackage
        SecurityException exception = assertThrows(SecurityException.class, () -> registry.loadVNode(vnodeFile));
        assertTrue(exception.getMessage().contains("Potential Path Traversal"));
    }

    private void createZip(Path path, String fileName, String content) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(path.toFile()))) {
            ZipEntry entry = new ZipEntry(fileName);
            zos.putNextEntry(entry);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
