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
     * Starts a command asynchronously with sandboxing and an explicit env-var grant list.
     *
     * <p>{@code envGrants} names caller-supplied {@code env} keys that must survive the
     * implementation's environment isolation policy (e.g. {@code ShellEnvPolicy} secret-pattern
     * excludes). The policy still governs everything inherited from the parent environment;
     * only the explicitly granted, explicitly named keys are exempt. Implementations that do
     * not support grants fall back to the policy-only behaviour (grants are dropped — the
     * fail-safe direction).
     */
    default VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir,
                                      VTrustLevel trustLevel, java.util.Set<String> envGrants) throws IOException {
        return startAsync(command, env, workingDir, trustLevel);
    }

    /**
     * Runs a trusted, read-only command for capability/version detection, <em>without</em> the
     * OS-native sandbox wrapper.
     *
     * <p>OS sandboxing (e.g. macOS {@code sandbox-exec}) is designed to constrain untrusted
     * plugin code, but it also blocks legitimate inspection tools that need broad read access —
     * {@code swift}/{@code xcrun}, {@code brew}, etc. fail or return nothing under it. This
     * method is intended for first-party scanners (DevEnv, Doctor) probing a fixed allow-list of
     * well-known binaries (e.g. {@code <tool> --version}); it does not take untrusted input and
     * performs no writes. The environment-isolation policy still applies (secrets are stripped).
     *
     * <p>Default implementation: {@code execute(command, Map.of(), null, VTrustLevel.FULL)}.
     */
    default VProcessResult probe(List<String> command) throws IOException {
        return execute(command, Map.of(), null, VTrustLevel.FULL);
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

