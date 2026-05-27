package dev.vatn.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

/**
 * Reads the {@code [sandbox.shell_env]} section from a TOML config file
 * and applies an environment-variable isolation policy before any subprocess is spawned.
 *
 * <p>Config file resolution order:
 * <ol>
 *   <li>Path provided via {@code vatn.config} system property</li>
 *   <li>{@code {workspace}/.vatn/vatn.toml}</li>
 *   <li>Safe default ({@code inherit = "core"}) if neither is found</li>
 * </ol>
 *
 * <p>Policy options ({@code inherit} field):
 * <ul>
 *   <li>{@code all}  — pass the full parent environment (legacy, unsafe default)</li>
 *   <li>{@code core} — keep only a safe set of non-secret vars (PATH, HOME, JAVA_HOME, …)</li>
 *   <li>{@code none} — start with an empty environment, only {@code set} entries are added</li>
 * </ul>
 *
 * <p>Example config stanza:
 * <pre>
 * [sandbox.shell_env]
 * inherit = "core"
 * exclude = ["AWS_*", "AZURE_*", "*_KEY", "*_SECRET", "*_TOKEN"]
 * set = { CI = "true" }
 * </pre>
 */
public final class ShellEnvPolicy {

    private static final Logger LOG = Logger.getLogger(ShellEnvPolicy.class.getName());

    /** Vars always kept in "core" inherit mode regardless of exclude patterns. */
    private static final Set<String> CORE_SAFE_VARS = Set.of(
            "PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE",
            "USER", "LOGNAME", "TERM", "SHELL", "TMPDIR", "TMP", "TEMP",
            "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME", "XDG_DATA_HOME",
            "COLORTERM", "TERM_PROGRAM", "CI",
            "MAVEN_HOME", "M2_HOME", "GRADLE_HOME", "GRADLE_USER_HOME",
            "NODE_PATH", "npm_config_cache",
            "GOPATH", "GOROOT", "CARGO_HOME", "RUSTUP_HOME"
    );

    // -----------------------------------------------------------------------
    // Parsed policy fields
    // -----------------------------------------------------------------------

    private final String inherit;           // "all" | "core" | "none"
    private final List<String> exclude;     // glob patterns
    private final Map<String, String> set;  // forced overrides

    private ShellEnvPolicy(String inherit, List<String> exclude, Map<String, String> set) {
        this.inherit = inherit;
        this.exclude = Collections.unmodifiableList(exclude);
        this.set     = Collections.unmodifiableMap(set);
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Loads the policy from configuration. Resolution order:
     * 1. {@code vatn.config} system property (explicit path to a TOML file)
     * 2. {@code {workspace}/.vatn/vatn.toml}
     * 3. Safe default (inherit = "core") if no config is found.
     */
    public static ShellEnvPolicy load() {
        // 1. Explicit override
        String configProp = System.getProperty("vatn.config");
        if (configProp != null && !configProp.isBlank()) {
            return loadFromPath(Paths.get(configProp));
        }
        // 2. Default location
        Path workspace = Paths.get(System.getProperty("user.dir"));
        return loadFromPath(workspace.resolve(".vatn/vatn.toml"));
    }

    /**
     * Loads the policy from the given TOML file path.
     * Returns the safe default if the file is absent or unreadable.
     */
    public static ShellEnvPolicy loadFromPath(Path toml) {
        if (!Files.exists(toml)) {
            return safeDefault();
        }
        try {
            return parse(Files.readString(toml));
        } catch (IOException e) {
            LOG.warning("[ShellEnvPolicy] Could not read config: " + e.getMessage());
            return safeDefault();
        }
    }

    /** Safe default used when no policy is configured. */
    public static ShellEnvPolicy safeDefault() {
        return new ShellEnvPolicy("core",
                List.of("AWS_*", "AZURE_*", "*_KEY", "*_SECRET", "*_TOKEN",
                        "ANTHROPIC_*", "OPENAI_*", "GEMINI_*"),
                Map.of("CI", "true"));
    }

    /** A fully permissive policy (legacy behaviour, use only in tests). */
    public static ShellEnvPolicy passthrough() {
        return new ShellEnvPolicy("all", List.of(), Map.of());
    }

    // -----------------------------------------------------------------------
    // Apply
    // -----------------------------------------------------------------------

    /**
     * Applies this policy to the given mutable environment map (as returned by
     * {@link ProcessBuilder#environment()}).
     *
     * @param env the mutable environment to filter in-place
     */
    public void applyTo(Map<String, String> env) {
        switch (inherit) {
            case "none" -> env.clear();
            case "core" -> {
                env.keySet().removeIf(k -> !CORE_SAFE_VARS.contains(k));
                // Still apply exclude to core vars (e.g. if someone added CI=token)
                env.keySet().removeIf(k -> matchesAny(k, exclude));
            }
            case "all"  -> env.keySet().removeIf(k -> matchesAny(k, exclude));
            default     -> {
                LOG.warning("[ShellEnvPolicy] Unknown inherit mode: " + inherit + ". Using 'core'.");
                env.keySet().removeIf(k -> !CORE_SAFE_VARS.contains(k));
                env.keySet().removeIf(k -> matchesAny(k, exclude));
            }
        }
        // Apply forced overrides (always added last)
        env.putAll(set);
    }

    // -----------------------------------------------------------------------
    // Glob matching
    // -----------------------------------------------------------------------

    /** Returns true if {@code key} matches any of the given glob patterns. */
    static boolean matchesAny(String key, List<String> patterns) {
        for (String pattern : patterns) {
            if (globMatch(key, pattern)) return true;
        }
        return false;
    }

    /**
     * Minimal glob matcher supporting {@code *} as wildcard.
     * e.g. {@code "*_KEY"} matches {@code "MY_SECRET_KEY"}.
     */
    static boolean globMatch(String text, String pattern) {
        if (!pattern.contains("*")) return pattern.equals(text);
        // Convert glob to regex: escape dots, convert * to .*
        String regex = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$";
        return text.matches(regex);
    }

    // -----------------------------------------------------------------------
    // TOML parser (simple, no external library)
    // -----------------------------------------------------------------------

    /**
     * Minimal parser for the {@code [sandbox.shell_env]} section.
     * Only handles the three fields we care about.
     */
    static ShellEnvPolicy parse(String tomlContent) {
        String inherit = "core";
        List<String> exclude = new ArrayList<>();
        Map<String, String> set = new LinkedHashMap<>();

        boolean inSection = false;
        for (String rawLine : tomlContent.split("\\r?\\n")) {
            String line = rawLine.trim();

            // Section headers
            if (line.startsWith("[")) {
                inSection = "[sandbox.shell_env]".equals(line);
                continue;
            }
            if (!inSection) continue;
            if (line.isEmpty() || line.startsWith("#")) continue;

            // inherit = "core"
            if (line.startsWith("inherit")) {
                inherit = extractQuotedValue(line);
            }
            // exclude = ["AWS_*", "*_KEY"]
            else if (line.startsWith("exclude")) {
                exclude = extractStringList(line);
            }
            // set = { CI = "true", FOO = "bar" }
            else if (line.startsWith("set")) {
                set = extractInlineTable(line);
            }
        }
        return new ShellEnvPolicy(inherit, exclude, set);
    }

    private static String extractQuotedValue(String line) {
        int start = line.indexOf('"');
        int end   = line.lastIndexOf('"');
        if (start < 0 || end <= start) return "core";
        return line.substring(start + 1, end);
    }

    private static List<String> extractStringList(String line) {
        int lb = line.indexOf('[');
        int rb = line.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return List.of();
        String inner = line.substring(lb + 1, rb);
        List<String> result = new ArrayList<>();
        for (String token : inner.split(",")) {
            String v = token.trim().replaceAll("^\"|\"$", "");
            if (!v.isEmpty()) result.add(v);
        }
        return result;
    }

    private static Map<String, String> extractInlineTable(String line) {
        int lb = line.indexOf('{');
        int rb = line.lastIndexOf('}');
        if (lb < 0 || rb <= lb) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        String inner = line.substring(lb + 1, rb);
        for (String pair : inner.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String k = kv[0].trim();
                String v = kv[1].trim().replaceAll("^\"|\"$", "");
                map.put(k, v);
            }
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Accessors (for tests and DoctorService)
    // -----------------------------------------------------------------------

    public String inheritMode() { return inherit; }
    public List<String> excludePatterns() { return exclude; }
    public Map<String, String> forcedSet() { return set; }

    @Override
    public String toString() {
        return "ShellEnvPolicy{inherit=" + inherit
                + ", exclude=" + exclude
                + ", set=" + set + "}";
    }
}
