package dev.vatn.cli.commands;

import dev.vatn.core.utils.VatnConfigManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "registry", description = "Manages federated plugin registries.")
public class RegistryCommand implements Callable<Integer> {

    @Command(name = "add", description = "Adds a new federated registry node.")
    public Integer add(
        @Parameters(index = "0", description = "Registry Node ID (e.g. vatn-hub-1)") String id,
        @Parameters(index = "1", description = "Registry URI (e.g. http://192.168.1.50:8080)") String url
    ) {
        VatnConfigManager config = new VatnConfigManager();
        config.addRegistry(id, url);
        System.out.println("✅ Registered federated node: " + id + " -> " + url);
        return 0;
    }

    @Command(name = "list", description = "Lists all registered federated nodes.")
    public Integer list() {
        VatnConfigManager config = new VatnConfigManager();
        Map<String, String> registries = config.getRegistries();
        if (registries.isEmpty()) {
            System.out.println("No federated registries configured. (Local only mode)");
        } else {
            System.out.println("📡 Federated Registries:");
            registries.forEach((id, url) -> System.out.println(" - " + id + ": " + url));
        }
        return 0;
    }

    @Override
    public Integer call() {
        return 0; // Handled by subcommands
    }
}
