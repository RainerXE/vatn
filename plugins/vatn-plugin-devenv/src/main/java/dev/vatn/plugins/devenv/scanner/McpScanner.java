package dev.vatn.plugins.devenv.scanner;

import dev.vatn.api.VJson;
import dev.vatn.plugins.devenv.model.McpEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses MCP server configurations from the configs of detected coding agents. Records env
 * variable <em>names</em> only — never values (DCN-DEV-12). JSON navigation is a static,
 * testable function over an already-parsed map; file reading + parsing is the thin glue.
 */
public final class McpScanner {

    /** A config file location and the app it belongs to. */
    private record Source(String app, Path path, String key) {}

    private final VJson json;

    public McpScanner(VJson json) {
        this.json = json;
    }

    public List<McpEntry> scan() {
        var sources = List.of(
                new Source("claude-desktop", ScannerUtil.homeDir("Library/Application Support/Claude/claude_desktop_config.json"), "mcpServers"),
                new Source("claude-desktop", ScannerUtil.homeDir(".config/claude/claude_desktop_config.json"), "mcpServers"),
                new Source("claude-code", ScannerUtil.homeDir(".claude.json"), "mcpServers"),
                new Source("cursor", ScannerUtil.homeDir(".cursor/mcp.json"), "mcpServers"),
                new Source("windsurf", ScannerUtil.homeDir(".codeium/windsurf/mcp_config.json"), "mcpServers"),
                new Source("zed", ScannerUtil.homeDir(".config/zed/settings.json"), "context_servers"));

        var out = new ArrayList<McpEntry>();
        for (Source s : sources) {
            ScannerUtil.readString(s.path()).ifPresent(content -> {
                try {
                    Map<?, ?> root = json.parse(content, Map.class);
                    out.addAll(fromConfigMap(root, s.app(), s.key()));
                } catch (Exception ignored) {
                    // malformed config — skip this source
                }
            });
        }
        return List.copyOf(out);
    }

    /**
     * Extract MCP entries from a parsed config map. {@code serversKey} is "mcpServers" or
     * (Zed) "context_servers". Handles both flat ({@code command/args/env} at the server level)
     * and nested ({@code command:{path,args,env}}, Zed-style) shapes.
     */
    static List<McpEntry> fromConfigMap(Map<?, ?> root, String app, String serversKey) {
        var out = new ArrayList<McpEntry>();
        Object servers = root.get(serversKey);
        if (!(servers instanceof Map<?, ?> map)) return out;

        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> cfg)) continue;

            // Zed nests under a "command" object; Claude/Cursor put it flat.
            Map<?, ?> src = cfg.get("command") instanceof Map<?, ?> nested ? nested : cfg;

            String command = src.get("command") instanceof String c ? c
                    : src.get("path") instanceof String p ? p : "";
            out.add(new McpEntry(name, command, strList(src.get("args")), keys(src.get("env")), app));
        }
        return out;
    }

    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        var out = new ArrayList<String>();
        for (Object item : list) out.add(String.valueOf(item));
        return List.copyOf(out);
    }

    /** Env variable NAMES only — values are never read or stored. */
    private static List<String> keys(Object o) {
        if (!(o instanceof Map<?, ?> map)) return List.of();
        var out = new ArrayList<String>();
        for (Object k : map.keySet()) out.add(String.valueOf(k));
        return List.copyOf(out);
    }
}
