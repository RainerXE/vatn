package dev.vatn.api.cli;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.io.PrintStream;
import java.util.Optional;

/**
 * Runtime context handed to a {@link VCliCommand} for the duration of one invocation.
 *
 * <p>What's available depends on the command's declared context
 * ({@code NONE}/{@code EPHEMERAL_NODE}/{@code REMOTE_NODE}/{@code ATTACH_OR_EPHEMERAL}):
 * for node-backed contexts {@link #node()} and {@link #getService} resolve against a booted or
 * attached node; for {@code NONE} they return {@code null}/empty. The context is
 * {@link AutoCloseable} — the host closes it after the command returns, tearing down any
 * ephemeral node.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VCliContext extends AutoCloseable {

    /** The node context, or {@code null} if the command declared {@code NONE}. */
    VNodeContext node();

    /** Convenience service lookup; empty if there is no node or the service is absent. */
    <T extends VService> Optional<T> getService(Class<T> type);

    /** Standard output for command results. */
    PrintStream out();

    /** Standard error for diagnostics. */
    PrintStream err();

    /** Tear down anything this context owns (e.g. an ephemeral node). Never throws. */
    @Override
    void close();
}
