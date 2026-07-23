package dev.vatn.plugins.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Minimal reader/writer for a named section in {@code .vatn/vatn.toml}.
 *
 * <p>Reads key = "value" pairs from a {@code [section]} block.
 * Writing updates existing keys in-place or appends a new section at the end.
 * Only handles simple string values; ignores arrays, inline tables, and comments.
 *
 * <p>Example section managed by this class:
 * <pre>
 * [python]
 * envs_dir = "/data/vatn/python/envs"
 * apps_dir  = "/data/vatn/python/apps"
 * python_binary = "python3.12"
 * prefer_uv = true
 * </pre>
 */
class RuntimeTomlConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeTomlConfig.class);

    private final Path   tomlPath;
    private final String section;   // e.g. "python" or "node"

    RuntimeTomlConfig(Path workspacePath, String section) {
        this.tomlPath = workspacePath.resolve(".vatn/vatn.toml");
        this.section  = section;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns all key=value pairs from the named section.
     * Returns an empty map if the file or section does not exist.
     */
    Map<String, String> read() {
        if (!Files.exists(tomlPath)) return Map.of();
        try {
            String content = Files.readString(tomlPath, StandardCharsets.UTF_8);
            return parse(content, section);
        } catch (IOException e) {
            log.warn("[TOML] Could not read {}: {}", tomlPath, e.getMessage());
            return Map.of();
        }
    }

    /** Convenience — get a single string value (null if absent). */
    String getString(String key) {
        return read().get(key);
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Saves {@code values} into the named section of the TOML file.
     * Creates the file if it does not exist. Adds the section if missing.
     * Updates existing keys in-place; preserves all other content.
     */
    void write(Map<String, String> values) throws IOException {
        String original = Files.exists(tomlPath)
            ? Files.readString(tomlPath, StandardCharsets.UTF_8) : "";

        String updated = applySection(original, section, values);
        Files.createDirectories(tomlPath.getParent());
        Files.writeString(tomlPath, updated, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("[TOML] Saved [{}] to {}", section, tomlPath);
    }

    // ── TOML parsing (minimal, handles only simple key = "value" or key = value) ──

    static Map<String, String> parse(String content, String targetSection) {
        Map<String, String> result = new LinkedHashMap<>();
        boolean inSection = false;

        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                // Section header — strip trailing comment
                String header = line.replaceAll("#.*$", "").trim();
                inSection = ("[" + targetSection + "]").equals(header);
                continue;
            }
            if (!inSection || line.isEmpty() || line.startsWith("#")) continue;

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim()
                .replaceAll("^[\"']|[\"']$", "")  // strip surrounding quotes
                .trim();
            result.put(key, val);
        }
        return result;
    }

    static String applySection(String original, String section, Map<String, String> values) {
        String sectionHeader = "[" + section + "]";
        String[] lines = original.split("\\r?\\n", -1);

        // Find the section start and end
        int sectionStart = -1;
        int sectionEnd   = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim().replaceAll("#.*$", "").trim();
            if (trimmed.equals(sectionHeader)) {
                sectionStart = i;
            } else if (sectionStart >= 0 && i > sectionStart
                    && trimmed.startsWith("[") && !trimmed.isEmpty()) {
                sectionEnd = i;
                break;
            }
        }

        if (sectionStart < 0) {
            // Section doesn't exist — append it
            StringBuilder sb = new StringBuilder(original);
            if (!original.endsWith("\n") && !original.isEmpty()) sb.append("\n");
            sb.append("\n").append(sectionHeader).append("\n");
            values.forEach((k, v) -> sb.append(k).append(" = \"").append(v).append("\"\n"));
            return sb.toString();
        }

        // Section exists — update/add keys within it
        Set<String> handled = new HashSet<>();
        List<String> result = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            if (i < sectionStart || i >= sectionEnd) {
                result.add(lines[i]);
                continue;
            }
            // Inside section
            if (i == sectionStart) { result.add(lines[i]); continue; }

            int eq = lines[i].indexOf('=');
            if (eq > 0) {
                String key = lines[i].substring(0, eq).trim();
                if (values.containsKey(key)) {
                    result.add(key + " = \"" + values.get(key) + "\"");
                    handled.add(key);
                    continue;
                }
            }
            result.add(lines[i]);
        }

        // Add keys not already in the section (insert before sectionEnd)
        int insertAt = result.size() - (lines.length - sectionEnd);
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!handled.contains(e.getKey())) {
                result.add(insertAt++, e.getKey() + " = \"" + e.getValue() + "\"");
            }
        }

        return String.join("\n", result);
    }
}
