package dev.vatn.api.cli;

import dev.vatn.api.VatnApi;

import java.util.List;

/**
 * A CLI subcommand contributed by a plugin (e.g. {@code vatn devenv …}).
 *
 * <p>Discovered by a host CLI (vatn, frejay, …) via {@link java.util.ServiceLoader} over the
 * plugin classpath, then mounted as a subcommand. This is the portable, framework-neutral
 * contract — the host adapts it to its CLI library (picocli, etc.) and supplies a
 * {@link VCliContext} matching the command's declared context. Plugins needing rich argument
 * parsing / interactive output may instead contribute a picocli {@code @Command} class named in
 * the manifest; this interface covers the common case.
 *
 * <p>Implementations must be public with a public no-arg constructor (ServiceLoader requirement)
 * and listed in {@code META-INF/services/dev.vatn.api.cli.VCliCommand}.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VCliCommand {

    /** Subcommand name, e.g. {@code "devenv"} → {@code vatn devenv …}. */
    String name();

    /** One-line summary shown in help. */
    default String summary() { return ""; }

    /** How this command wants its context provisioned. Default: a throwaway node. */
    default VCliContextMode contextMode() { return VCliContextMode.EPHEMERAL_NODE; }

    /**
     * Run the command.
     *
     * @param args the arguments following the subcommand name (the command parses its own)
     * @param ctx  node/services/IO for this invocation
     * @return process exit code (0 = success)
     */
    int run(List<String> args, VCliContext ctx) throws Exception;
}
