package dev.vatn.core;

import dev.vatn.api.VProcessService.VProcessHandle;
import dev.vatn.api.VProcessService.VProcessResult;
import dev.vatn.api.security.VTrustLevel;
import dev.vatn.core.ShellEnvPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class LocalProcessServiceTest {

    @Test
    public void testFullTrustLevelExecution() throws IOException {
        LocalProcessService service = new LocalProcessService();
        
        VProcessResult result = service.execute(
                List.of("echo", "hello sandbox"), 
                Map.of(), 
                null, 
                VTrustLevel.FULL
        );

        assertEquals(0, result.exitCode(), "FULL trust should succeed");
        assertTrue(result.stdout().contains("hello sandbox"), "Output should contain the echo text");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void testSandboxedFileWriteDenialOnMac() throws IOException {
        LocalProcessService service = new LocalProcessService();
        
        // Attempt to create a file
        File testFile = new File(System.getProperty("java.io.tmpdir"), "sandbox_test.txt");
        if (testFile.exists()) {
            testFile.delete();
        }

        VProcessResult result = service.execute(
                List.of("touch", testFile.getAbsolutePath()), 
                Map.of(), 
                null, 
                VTrustLevel.SANDBOXED
        );

        // Under macOS sandbox-exec with (deny file-write*), touch should fail
        assertNotEquals(0, result.exitCode(), "SANDBOXED trust should fail to write a file");
        assertTrue(result.stderr().contains("Operation not permitted") || result.stderr().contains("sandbox"),
                   "Stderr should indicate a permission error: " + result.stderr());
        assertFalse(testFile.exists(), "File should not have been created");
    }

    // -----------------------------------------------------------------------
    // Env grants (per-server env allowlist on top of ShellEnvPolicy)
    // -----------------------------------------------------------------------

    /** Policy with inherit="all" so only the exclude patterns govern caller-supplied env. */
    private static LocalProcessService serviceExcludingTokens() {
        ShellEnvPolicy policy = ShellEnvPolicy.parse(
                "[sandbox.shell_env]\n" +
                "inherit = \"all\"\n" +
                "exclude = [\"*_TOKEN\", \"*_KEY\"]\n");
        return new LocalProcessService(policy);
    }

    /** Reads $VAR from a started process's stdout (POSIX shell). */
    private static String echoVar(LocalProcessService service, String var,
                                  Map<String, String> env, Set<String> grants) throws IOException {
        VProcessHandle handle = service.startAsync(
                List.of("sh", "-c", "printf '%s' \"$" + var + "\""),
                env, null, VTrustLevel.FULL, grants);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(handle.stdout(), StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void grantedTokenSurvivesExcludePattern() throws IOException {
        String value = echoVar(serviceExcludingTokens(), "FOO_TOKEN",
                Map.of("FOO_TOKEN", "secret-123"), Set.of("FOO_TOKEN"));
        assertEquals("secret-123", value, "Granted *_TOKEN var should survive the exclude policy");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void ungrantedTokenIsStripped() throws IOException {
        String value = echoVar(serviceExcludingTokens(), "FOO_TOKEN",
                Map.of("FOO_TOKEN", "secret-123"), Set.of());
        assertEquals("", value, "Ungranted *_TOKEN var must be stripped by the exclude policy");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void grantWorksWithInheritNone() throws IOException {
        ShellEnvPolicy policy = ShellEnvPolicy.parse(
                "[sandbox.shell_env]\ninherit = \"none\"\n");
        LocalProcessService service = new LocalProcessService(policy);
        String value = echoVar(service, "API_KEY",
                Map.of("API_KEY", "k-456"), Set.of("API_KEY"));
        assertEquals("k-456", value, "Grant should re-add a named var even under inherit=none");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void nonSecretCallerVarSurvivesWithoutGrant() throws IOException {
        String value = echoVar(serviceExcludingTokens(), "FOO_PLAIN",
                Map.of("FOO_PLAIN", "ok"), Set.of());
        assertEquals("ok", value, "Non-excluded caller var should pass through without a grant");
    }
}
