package dev.vatn.api.cli;

import dev.vatn.api.VatnApi;

/**
 * How a {@link VCliCommand} wants its {@link VCliContext} provisioned for an invocation.
 * Mirrors the manifest {@code contributes.commands[].context}; for ServiceLoader-discovered
 * JVM commands this method on the command is authoritative.
 */
@VatnApi(since = "1.0-alpha.15")
public enum VCliContextMode {
    /** Pure command; no node. */
    NONE,
    /** Boot a throwaway in-process node for the run. */
    EPHEMERAL_NODE,
    /** Attach to a running node (host-supplied address). */
    REMOTE_NODE,
    /** Attach to a running daemon if present, else boot an ephemeral node. */
    ATTACH_OR_EPHEMERAL
}
