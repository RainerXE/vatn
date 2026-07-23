package dev.vatn.plugins.devenv.model;

/**
 * A detected AI coding agent or editor/IDE.
 *
 * @param name       e.g. "claude", "cursor", "windsurf", "aider", "code", "zed"
 * @param version    version string ("" if unknown)
 * @param path       resolved binary or app path ("" if detected by app bundle only)
 * @param configPath primary config/MCP path if known ("")
 * @param type       "CLI" | "GUI" | "IDE"
 */
public record AgentEntry(String name, String version, String path, String configPath, String type) {
}
