package dev.vatn.cli.commands;

import dev.vatn.api.VPluginDescriptor;
import dev.vatn.core.VNodeRunner;
import dev.vatn.core.VPluginDescriptorImpl;
import dev.vatn.core.VJsonImpl;
import dev.vatn.core.supervisor.VWatchdogServiceImpl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@Command(name = "run", description = "Starts a VATN node and launches a plugin.")
public class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the vatn-plugin.json manifest.")
    private File manifestFile;

    @Option(names = {"-p", "--port"}, description = "Port to run the node on.", defaultValue = "8080")
    private int port;

    @Override
    public Integer call() throws Exception {
        if (!manifestFile.exists()) {
            System.err.println("Error: Manifest file not found at " + manifestFile.getAbsolutePath());
            System.err.println("       Run 'vatn init' to create a project, then 'vatn run <manifest>' from that directory.");
            return 1;
        }

        System.out.println("🚀 Starting VATN Node on port " + port + "...");
        
        // 1. Initialize Runtime
        VNodeRunner node = VNodeRunner.create(port);
        VJsonImpl json = new VJsonImpl();
        
        // 2. Load Manifest
        String jsonContent = Files.readString(manifestFile.toPath());
        dev.vatn.spec.VPluginManifest manifest = json.parse(jsonContent, dev.vatn.spec.VPluginManifest.class);
        VPluginDescriptor descriptor = new VPluginDescriptorImpl(manifest.getId(), manifest, manifestFile.toPath());
        
        System.out.println("📦 Loading Plugin: " + manifest.getName() + " [" + manifest.getId() + "]");

        // 3. Initialize Watchdog (for OUT_OF_PROCESS_BIN support)
        VWatchdogServiceImpl watchdog = new VWatchdogServiceImpl(null); // Watchdog can accept context later or we fix constructor
        node.registerService(dev.vatn.api.VWatchdogService.class, watchdog);
        watchdog.start();

        // 4. Register Plugin
        String executionMode = descriptor.getManifest().getExecution().getMode();
        if ("OUT_OF_PROCESS_BIN".equals(executionMode)) {
            System.out.println("🛠 Launching via Watchdog (Binary Mode)...");
            watchdog.supervise(descriptor.getPluginId(), descriptor);
        } else {
            System.err.println("Error: Execution mode " + executionMode + " not yet supported by standard CLI. Use .vnode for shared plugins.");
            return 1;
        }

        // 5. Start Node
        node.start();
        
        System.out.println("✅ Node is READY and Supervised. Press Ctrl+C to stop.");
        
        // Block until shutdown (Wait for thread or signal)
        Thread.currentThread().join();

        return 0;
    }
}
