package dev.vatn.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ShellEnvPolicy} — the env-isolation policy read from
 * {@code [sandbox.shell_env]} in {@code .vatn/vatn.toml}.
 *
 * <p>Ported from Frejay's ShellEnvPolicyTest and extended for VATN's API:
 * {@code applyTo(Map)} modifies a map in-place, {@code matchesAny} / {@code globMatch}
 * are static helpers, and {@code loadFromPath(Path)} is the file-based factory.
 */
class ShellEnvPolicyTest {

    // ── TOML parsing ──────────────────────────────────────────────────────────

    @Test
    void parse_extractsInheritExcludeAndSet() {
        String toml = """
                [model]
                strong = "claude"

                [sandbox.shell_env]
                inherit = "core"
                exclude = ["AWS_*", "*_TOKEN", "ANTHROPIC_*"]
                set = { CI = "true" }

                [guard]
                level = "balanced"
                """;
        ShellEnvPolicy policy = ShellEnvPolicy.parse(toml);

        assertEquals("core", policy.inheritMode());
        assertTrue(policy.excludePatterns().contains("AWS_*"));
        assertTrue(policy.excludePatterns().contains("*_TOKEN"));
        assertEquals("true", policy.forcedSet().get("CI"));
    }

    @Test
    void parse_stopsReadingAtNextSection() {
        String toml = """
                [sandbox.shell_env]
                inherit = "none"
                exclude = ["SECRET_*"]

                [other_section]
                inherit = "all"
                """;
        ShellEnvPolicy policy = ShellEnvPolicy.parse(toml);
        assertEquals("none", policy.inheritMode());
        assertEquals(1, policy.excludePatterns().size());
    }

    @Test
    void parse_returnsCoreSafeDefaultWhenSectionAbsent() {
        ShellEnvPolicy policy = ShellEnvPolicy.parse("[model]\nstrong = \"claude\"\n");
        // parse() returns a ShellEnvPolicy even with no [sandbox.shell_env] section;
        // the defaults (inherit=core, standard excludes) apply when loaded from disk,
        // but parse() with no section simply produces an empty-exclude core policy.
        assertNotNull(policy);
        assertEquals("core", policy.inheritMode());
    }

    // ── Glob matching ─────────────────────────────────────────────────────────

    @Test
    void globMatch_prefixWildcard() {
        assertTrue(ShellEnvPolicy.globMatch("ANTHROPIC_API_KEY", "ANTHROPIC_*"));
        assertFalse(ShellEnvPolicy.globMatch("PATH", "ANTHROPIC_*"));
    }

    @Test
    void globMatch_suffixWildcard() {
        assertTrue(ShellEnvPolicy.globMatch("MY_SECRET_KEY", "*_KEY"));
        assertTrue(ShellEnvPolicy.globMatch("GITHUB_TOKEN", "*_TOKEN"));
        assertFalse(ShellEnvPolicy.globMatch("PATH", "*_KEY"));
    }

    @Test
    void globMatch_bothWildcards() {
        assertTrue(ShellEnvPolicy.globMatch("FOO_SECRET_BAR", "*SECRET*"));
        assertFalse(ShellEnvPolicy.globMatch("PATH", "*SECRET*"));
    }

    @Test
    void globMatch_exactMatch() {
        assertTrue(ShellEnvPolicy.globMatch("CI", "CI"));
        assertFalse(ShellEnvPolicy.globMatch("CID", "CI"));
    }

    @Test
    void matchesAny_trueOnFirstHit() {
        List<String> patterns = List.of("AWS_*", "*_TOKEN", "ANTHROPIC_*");
        assertTrue(ShellEnvPolicy.matchesAny("ANTHROPIC_API_KEY", patterns));
        assertTrue(ShellEnvPolicy.matchesAny("MY_TOKEN", patterns));
        assertFalse(ShellEnvPolicy.matchesAny("PATH", patterns));
    }

    // ── safeDefault() ─────────────────────────────────────────────────────────

    @Test
    void safeDefault_excludesStandardSecretPatterns() {
        ShellEnvPolicy policy = ShellEnvPolicy.safeDefault();
        List<String> exclude = policy.excludePatterns();

        assertTrue(ShellEnvPolicy.matchesAny("ANTHROPIC_API_KEY",      exclude));
        assertTrue(ShellEnvPolicy.matchesAny("AWS_SECRET_ACCESS_KEY",  exclude));
        assertTrue(ShellEnvPolicy.matchesAny("OPENAI_API_KEY",         exclude));
        assertTrue(ShellEnvPolicy.matchesAny("MY_SECRET_KEY",          exclude));
        assertTrue(ShellEnvPolicy.matchesAny("GITHUB_TOKEN",           exclude));
        assertTrue(ShellEnvPolicy.matchesAny("GEMINI_API_KEY",         exclude));
    }

    @Test
    void safeDefault_doesNotExcludeCoreVars() {
        ShellEnvPolicy policy = ShellEnvPolicy.safeDefault();
        List<String> exclude = policy.excludePatterns();

        assertFalse(ShellEnvPolicy.matchesAny("PATH",      exclude));
        assertFalse(ShellEnvPolicy.matchesAny("HOME",      exclude));
        assertFalse(ShellEnvPolicy.matchesAny("JAVA_HOME", exclude));
    }

    @Test
    void safeDefault_forcedSetContainsCI() {
        ShellEnvPolicy policy = ShellEnvPolicy.safeDefault();
        assertEquals("true", policy.forcedSet().get("CI"));
    }

    // ── applyTo() — inherit modes ─────────────────────────────────────────────

    @Test
    void applyTo_none_producesOnlyForcedSet() {
        ShellEnvPolicy policy = ShellEnvPolicy.parse("""
                [sandbox.shell_env]
                inherit = "none"
                exclude = []
                set = { MY_VAR = "hello" }
                """);
        Map<String, String> env = sampleEnv();
        policy.applyTo(env);

        assertEquals("hello", env.get("MY_VAR"),  "set key must be present");
        assertFalse(env.containsKey("PATH"),       "PATH must be absent under 'none'");
        assertFalse(env.containsKey("MY_SECRET"),  "secrets must be absent under 'none'");
    }

    @Test
    void applyTo_all_keepsEverythingExceptExcludes() {
        ShellEnvPolicy policy = ShellEnvPolicy.parse("""
                [sandbox.shell_env]
                inherit = "all"
                exclude = ["MY_SECRET"]
                set = {}
                """);
        Map<String, String> env = sampleEnv();
        policy.applyTo(env);

        assertTrue(env.containsKey("PATH"),       "PATH must survive 'all'");
        assertFalse(env.containsKey("MY_SECRET"), "excluded key must be removed");
    }

    @Test
    void applyTo_core_keepsOnlySafeVarsAndForcedSet() {
        ShellEnvPolicy policy = ShellEnvPolicy.parse("""
                [sandbox.shell_env]
                inherit = "core"
                exclude = []
                set = { CI = "true" }
                """);
        Map<String, String> env = sampleEnv();
        env.put("HOME", "/home/user");
        env.put("JAVA_HOME", "/usr/lib/jvm");
        policy.applyTo(env);

        assertTrue(env.containsKey("PATH"),      "PATH must survive 'core'");
        assertTrue(env.containsKey("HOME"),      "HOME must survive 'core'");
        assertTrue(env.containsKey("JAVA_HOME"), "JAVA_HOME must survive 'core'");
        assertTrue(env.containsKey("CI"),        "forced set must be added");
        assertFalse(env.containsKey("MY_SECRET"), "unsafe key must be removed under 'core'");
        assertFalse(env.containsKey("FOO_BAR"),   "unknown key must be removed under 'core'");
    }

    @Test
    void applyTo_forcedSetOverridesExistingValue() {
        ShellEnvPolicy policy = ShellEnvPolicy.parse("""
                [sandbox.shell_env]
                inherit = "all"
                exclude = []
                set = { PATH = "/safe/bin" }
                """);
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/original");
        policy.applyTo(env);

        assertEquals("/safe/bin", env.get("PATH"), "forced set must override original value");
    }

    @Test
    void applyTo_coreExcludeCatchesResidualMatch() {
        ShellEnvPolicy policy = ShellEnvPolicy.parse("""
                [sandbox.shell_env]
                inherit = "core"
                exclude = ["CI"]
                set = {}
                """);
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "CI", "true"));
        policy.applyTo(env);

        // CI is in CORE_SAFE_VARS but was explicitly excluded
        assertFalse(env.containsKey("CI"), "exclude must remove vars that survived core filter");
        assertTrue(env.containsKey("PATH"));
    }

    // ── loadFromPath() ────────────────────────────────────────────────────────

    @Test
    void loadFromPath_returnsDefaultWhenFileMissing(@TempDir Path tmp) {
        ShellEnvPolicy policy = ShellEnvPolicy.loadFromPath(tmp.resolve(".vatn/vatn.toml"));
        assertNotNull(policy);
        assertEquals("core", policy.inheritMode(), "should default to core when file is absent");
    }

    @Test
    void loadFromPath_readsFileCorrectly(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve(".vatn");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("vatn.toml"), """
                [sandbox.shell_env]
                inherit = "core"
                exclude = ["MY_CUSTOM_SECRET"]
                set = { FOO = "bar" }
                """);
        ShellEnvPolicy policy = ShellEnvPolicy.loadFromPath(dir.resolve("vatn.toml"));

        assertTrue(ShellEnvPolicy.matchesAny("MY_CUSTOM_SECRET", policy.excludePatterns()));
        assertEquals("bar", policy.forcedSet().get("FOO"));
    }

    @Test
    void loadFromPath_corruptFileReturnsDefault(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("vatn.toml");
        Files.writeString(toml, "this is not valid toml: {{{");
        // Should not throw; returns safe default instead
        ShellEnvPolicy policy = assertDoesNotThrow(() -> ShellEnvPolicy.loadFromPath(toml));
        assertNotNull(policy);
    }

    // ── passthrough() ─────────────────────────────────────────────────────────

    @Test
    void passthrough_leavesMapUnchanged() {
        ShellEnvPolicy policy = ShellEnvPolicy.passthrough();
        Map<String, String> env = sampleEnv();
        int original = env.size();
        policy.applyTo(env);
        assertEquals(original, env.size(), "passthrough must not remove any keys");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, String> sampleEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH",      "/usr/bin:/usr/local/bin");
        env.put("MY_SECRET", "super-secret-value");
        env.put("FOO_BAR",   "some-random-var");
        return env;
    }
}
