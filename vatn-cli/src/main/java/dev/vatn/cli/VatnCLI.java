package dev.vatn.cli;

import dev.vatn.cli.commands.InitCommand;
import dev.vatn.cli.commands.RunCommand;
import dev.vatn.cli.commands.RegistryCommand;
import dev.vatn.cli.commands.LogsCommand;
import dev.vatn.cli.commands.InfoCommand;
import dev.vatn.cli.commands.TestCommand;
import dev.vatn.cli.commands.OipcBenchmarkCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "vatn", 
         mixinStandardHelpOptions = true, 
         version = "VATN Runtime 1.0.0",
         description = "The Federated OS for Personal AI - Standalone Runtime CLI.",
         subcommands = {
             RunCommand.class,
             InitCommand.class,
             RegistryCommand.class,
             LogsCommand.class,
             InfoCommand.class,
             TestCommand.class,
             OipcBenchmarkCommand.class
         })
public class VatnCLI implements Callable<Integer> {

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging.")
    private boolean verbose;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new VatnCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // If no subcommand is provided, just show the help
        CommandLine.usage(this, System.out);
        return 0;
    }
}
