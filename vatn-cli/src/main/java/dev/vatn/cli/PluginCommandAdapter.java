package dev.vatn.cli;

import dev.vatn.api.cli.VCliCommand;
import dev.vatn.api.cli.VCliContext;
import dev.vatn.core.cli.CliContexts;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Unmatched;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Adapts a plugin-contributed {@link VCliCommand} to a picocli subcommand. All trailing tokens
 * (positional and unknown options) are forwarded verbatim so the plugin parses its own args
 * (per the neutral SPI contract). The matching {@link VCliContext} is built and torn down here.
 */
@Command(mixinStandardHelpOptions = false)
public final class PluginCommandAdapter implements Callable<Integer> {

    private final VCliCommand command;

    @Parameters(hidden = true)
    private List<String> positionals = new ArrayList<>();

    @Unmatched
    private List<String> unmatched = new ArrayList<>();

    public PluginCommandAdapter(VCliCommand command) {
        this.command = command;
    }

    /** Build a picocli subcommand that forwards everything to the plugin. */
    static CommandLine wrap(VCliCommand command) {
        CommandLine cl = new CommandLine(new PluginCommandAdapter(command));
        cl.getCommandSpec().usageMessage().description(command.summary());
        cl.setUnmatchedArgumentsAllowed(true);
        cl.setUnmatchedOptionsArePositionalParams(true);
        return cl;
    }

    @Override
    public Integer call() throws Exception {
        List<String> args = new ArrayList<>(positionals);
        args.addAll(unmatched);
        try (VCliContext ctx = CliContexts.forMode(command.contextMode())) {
            return command.run(args, ctx);
        }
    }
}
