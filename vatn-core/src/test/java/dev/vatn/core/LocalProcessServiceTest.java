package dev.vatn.core;

import dev.vatn.api.VProcessService.VProcessResult;
import dev.vatn.api.security.VTrustLevel;
import dev.vatn.core.ShellEnvPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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
}
