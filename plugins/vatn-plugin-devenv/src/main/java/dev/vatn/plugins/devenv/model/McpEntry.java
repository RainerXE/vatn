package dev.vatn.plugins.devenv.model;

import java.util.List;

/**
 * An MCP server configuration discovered in a coding agent's config.
 *
 * <p>Security: {@code envKeys} carries environment-variable <em>names</em> only — never values
 * (DCN-DEV-12 / MCP keys-only rule).
 *
 * @param serverName configured server name
 * @param command    launch command ("" for remote/url transports)
 * @param args       command arguments
 * @param envKeys    environment variable names referenced (NOT their values)
 * @param sourceApp  which app's config this came from (claude-desktop, cursor, …)
 */
public record McpEntry(String serverName, String command, List<String> args,
                       List<String> envKeys, String sourceApp) {
}
