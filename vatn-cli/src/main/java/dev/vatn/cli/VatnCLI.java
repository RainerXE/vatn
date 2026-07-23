package dev.vatn.cli;

import dev.vatn.cli.commands.InitCommand;
import dev.vatn.cli.commands.RunCommand;
import dev.vatn.cli.commands.RegistryCommand;
import dev.vatn.cli.commands.LogsCommand;
import dev.vatn.cli.commands.InfoCommand;
import dev.vatn.cli.commands.TestCommand;
import dev.vatn.cli.commands.OipcBenchmarkCommand;
import dev.vatn.cli.commands.InventoryCommand;
import dev.vatn.cli.commands.SelfUpdateCommand;
import dev.vatn.api.cli.VCliCommand;
import dev.vatn.core.cli.CliCommandLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "vatn",
         mixinStandardHelpOptions = true,
         versionProvider = VatnVersionProvider.class,
         description = "VATN — Runtime for Personal Services.",
         subcommands = {
             RunCommand.class,
             InitCommand.class,
             RegistryCommand.class,
             LogsCommand.class,
             InfoCommand.class,
             InventoryCommand.class,
             TestCommand.class,
             SelfUpdateCommand.class,
             OipcBenchmarkCommand.class
         })
public class VatnCLI implements Callable<Integer> {

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging.")
    private boolean verbose;

    public static void main(String[] args) {
        CommandLine root = new CommandLine(new VatnCLI());
        // Mount plugin-contributed CLI commands discovered from the cli-plugins dir.
        for (VCliCommand cmd : CliCommandLoader.discoverFrom(pluginsDir(), VatnCLI.class.getClassLoader())) {
            root.addSubcommand(cmd.name(), PluginCommandAdapter.wrap(cmd));
        }
        int exitCode = root.execute(args);
        System.exit(exitCode);
    }

    /** Directory of plugin jars contributing CLI commands ({@code VATN_CLI_PLUGINS} or ~/.vatn/cli-plugins). */
    private static Path pluginsDir() {
        String env = System.getenv("VATN_CLI_PLUGINS");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".vatn", "cli-plugins");
    }

    @Override
    public Integer call() {
        // If no subcommand is provided, just show the help
        CommandLine.usage(this, System.out);
        return 0;
    }
}
