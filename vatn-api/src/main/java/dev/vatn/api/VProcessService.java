package dev.vatn.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import dev.vatn.api.security.VTrustLevel;

/**
 * Universal Process Execution SPI.
 * Decouples agents/tools from the host OS process management.
 * In a secure VATN node, this might delegate to a WASM runtime, 
 * a Docker container, or a restricted local process.
 */
@VatnApi(since = "1.0")
public interface VProcessService extends VService {
    
    /**
     * Executes a command and returns the output.
     */
    VProcessResult execute(List<String> command, Map<String, String> env, String workingDir) throws IOException;

    /**
     * Executes a command with OS-native sandboxing applied based on the trust level.
     */
    default VProcessResult execute(List<String> command, Map<String, String> env, String workingDir, VTrustLevel trustLevel) throws IOException {
        return execute(command, env, workingDir);
    }

    /**
     * Starts a command asynchronously and returns a handle.
     */
    VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir) throws IOException;

    /**
     * Starts a command asynchronously with OS-native sandboxing applied based on the trust level.
     */
    default VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir, VTrustLevel trustLevel) throws IOException {
        return startAsync(command, env, workingDir);
    }

    /**
     * Data object for process execution results.
     */
    record VProcessResult(int exitCode, String stdout, String stderr) {}

    /**
     * Handle for an asynchronous process.
     */
    record VProcessHandle(long pid, java.io.InputStream stdout, java.io.InputStream stderr, java.io.OutputStream stdin) {}
}

