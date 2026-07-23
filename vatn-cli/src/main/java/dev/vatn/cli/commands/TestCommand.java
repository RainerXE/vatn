package dev.vatn.cli.commands;

import dev.vatn.api.VPluginDescriptor;
import dev.vatn.api.VWatchdogService;
import dev.vatn.core.VJsonImpl;
import dev.vatn.core.VPluginDescriptorImpl;
import dev.vatn.core.test.VTestHarness;
import dev.vatn.spec.VPluginManifest;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "test", description = "Runs integration and stability tests for the VATN plugin.")
public class TestCommand implements Callable<Integer> {

    @Option(names = {"-f", "--file"}, description = "Path to the vatn-plugin.json manifest.", defaultValue = "vatn-plugin.json")
    private File manifestFile;

    @Option(names = {"--timeout"}, description = "Stability audit timeout in seconds.", defaultValue = "5")
    private int timeout;

    @Override
    public Integer call() throws Exception {
        if (!manifestFile.exists()) {
            System.err.println("Error: Manifest file not found at " + manifestFile.getAbsolutePath());
            System.err.println("       Run 'vatn init' to create a project, or cd to the project directory.");
            System.err.println("       Use 'vatn test -f <path>' to specify a custom manifest path.");
            return 1;
        }

        System.out.println("🧪 Starting VATN Test Runner...");
        
        // 1. Load Manifest
        VJsonImpl json = new VJsonImpl();
        String jsonContent = Files.readString(manifestFile.toPath());
        VPluginManifest manifest = json.parse(jsonContent, VPluginManifest.class);
        VPluginDescriptor descriptor = new VPluginDescriptorImpl(manifest.getId(), manifest, manifestFile.toPath());

        System.out.println("📦 Testing Plugin: " + manifest.getName() + " [" + manifest.getId() + "]");

        // 2. Discover Native Tests
        Path testsDir = manifestFile.toPath().getParent().resolve("tests");

        if (Files.exists(testsDir) && Files.isDirectory(testsDir)) {
            if (Files.exists(testsDir.resolve("conftest.py")) || Files.exists(testsDir.resolve("pytest.ini"))) {
                System.out.println("🐍 Python (pytest) tests detected. Executing...");
                int exitCode = runProcess("pytest", testsDir.toAbsolutePath().toString());
                if (exitCode != 0) return exitCode;
            } else if (Files.exists(testsDir.resolve("pom.xml"))) {
                System.out.println("☕ Java (Maven) tests detected. Executing...");
                int exitCode = runProcess("mvn", "test", "-f", testsDir.resolve("pom.xml").toString());
                if (exitCode != 0) return exitCode;
            }
        }

        // 3. Stability Audit (VTestHarness)
        System.out.println("🛡 Running VATN Stability Audit (FailOnLimits=True)...");
        Path tempHome = Files.createTempDirectory("vatn-test-audit-");
        VTestHarness harness = new VTestHarness(0, tempHome, true);
        harness.start();

        try {
            System.out.println("⏳ Monitoring plugin for " + timeout + "s (Memory/Hangs)...");
            // Register Plugin for supervision
            harness.getContext().getService(VWatchdogService.class).ifPresent(watchdog -> {
                watchdog.supervise(descriptor.getPluginId(), descriptor);
            });
            
            // Wait for timeout
            Thread.sleep(timeout * 1000L);
            
            System.out.println("✅ Stability Audit Passed.");
        } catch (InterruptedException e) {
            System.err.println("❌ Stability Audit FAILED: " + e.getMessage());
            Thread.currentThread().interrupt();
            return 1;
        } finally {
            harness.stop();
        }

        System.out.println("\n🎉 ALL TESTS PASSED.");
        return 0;
    }

    private int runProcess(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }
}
