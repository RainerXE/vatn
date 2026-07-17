package dev.vatn.core;

import dev.vatn.api.VProcessService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import dev.vatn.api.admin.VWorkload;
import dev.vatn.api.security.VTrustLevel;
import dev.vatn.core.security.OsSandboxWrapper;

/**
 * Local implementation of VProcessService using java.lang.ProcessBuilder.
 *
 * <p>Applies {@link ShellEnvPolicy} before every subprocess is spawned,
 * preventing secret-pattern env vars (AWS_*, *_KEY, *_TOKEN, …) from leaking
 * into subprocess environments (A5 — GAP-05).
 *
 * <p>Policy is loaded from the workspace config (see {@link ShellEnvPolicy#load()}):
 * <pre>
 * [sandbox.shell_env]
 * inherit = "core"
 * exclude = ["AWS_*", "*_KEY", "*_SECRET", "*_TOKEN"]
 * set = { CI = "true" }
 * </pre>
 */
public class LocalProcessService implements VProcessService {

    private final ShellEnvPolicy policy;
    private final Map<Long, VWorkload> activeWorkloads = new ConcurrentHashMap<>();

    /** Constructs with policy loaded from the workspace configuration. */
    public LocalProcessService() {
        this.policy = ShellEnvPolicy.load();
    }

    /** Constructs with an explicit policy (useful for testing). */
    public LocalProcessService(ShellEnvPolicy policy) {
        this.policy = policy;
    }

    // -----------------------------------------------------------------------
    // VProcessService implementation
    // -----------------------------------------------------------------------

    public List<VWorkload> getActiveWorkloads() {
        return List.copyOf(activeWorkloads.values());
    }

    @Override
    public VProcessResult execute(List<String> command, Map<String, String> env, String workingDir)
            throws IOException {
        return execute(command, env, workingDir, VTrustLevel.FULL);
    }

    @Override
    public VProcessResult execute(List<String> command, Map<String, String> env, String workingDir, VTrustLevel trustLevel)
            throws IOException {

        ProcessBuilder pb = buildProcess(command, env, workingDir, trustLevel);
        Process process = pb.start();

        VWorkload workload = new VWorkload(
            String.valueOf(process.pid()),
            String.join(" ", command),
            VWorkload.Type.PROCESS,
            VWorkload.Status.RUNNING,
            Instant.now(),
            Map.of("pid", String.valueOf(process.pid()))
        );
        activeWorkloads.put(process.pid(), workload);
        process.onExit().thenRun(() -> activeWorkloads.remove(process.pid()));

        String stdout;
        String stderr;

        try (BufferedReader stdIn = new BufferedReader(
                     new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader stdErr = new BufferedReader(
                     new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            stdout = stdIn.lines().collect(Collectors.joining("\n"));
            stderr = stdErr.lines().collect(Collectors.joining("\n"));
        }

        try {
            int exitCode = process.waitFor();
            return new VProcessResult(exitCode, stdout, stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Process interrupted", e);
        }
    }

    @Override
    public VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir)
            throws IOException {
        return startAsync(command, env, workingDir, VTrustLevel.FULL);
    }

    @Override
    public VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir, VTrustLevel trustLevel)
            throws IOException {
        return startAsync(command, env, workingDir, trustLevel, java.util.Set.of());
    }

    @Override
    public VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir,
                                     VTrustLevel trustLevel, java.util.Set<String> envGrants)
            throws IOException {

        ProcessBuilder pb = buildProcess(command, env, workingDir, trustLevel, envGrants);
        Process process = pb.start();
        
        VWorkload workload = new VWorkload(
            String.valueOf(process.pid()),
            String.join(" ", command),
            VWorkload.Type.PROCESS,
            VWorkload.Status.RUNNING,
            Instant.now(),
            Map.of("pid", String.valueOf(process.pid()))
        );
        activeWorkloads.put(process.pid(), workload);
        process.onExit().thenRun(() -> activeWorkloads.remove(process.pid()));

        return new VProcessHandle(
                process.pid(),
                process.getInputStream(),
                process.getErrorStream(),
                process.getOutputStream()
        );
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Creates a configured ProcessBuilder with the isolation policy applied.
     *
     * <p>The policy runs AFTER the caller-supplied {@code env} overrides are merged
     * so that callers can force-add specific vars (e.g. JAVA_TOOL_OPTIONS) while
     * the policy still strips secret-pattern vars.
     */
    private ProcessBuilder buildProcess(List<String> command,
                                        Map<String, String> env,
                                        String workingDir,
                                        VTrustLevel trustLevel) {
        return buildProcess(command, env, workingDir, trustLevel, java.util.Set.of());
    }

    private ProcessBuilder buildProcess(List<String> command,
                                        Map<String, String> env,
                                        String workingDir,
                                        VTrustLevel trustLevel,
                                        java.util.Set<String> envGrants) {
        List<String> wrappedCommand = OsSandboxWrapper.wrapCommand(command, trustLevel);
        ProcessBuilder pb = new ProcessBuilder(wrappedCommand);

        if (workingDir != null && !workingDir.isEmpty()) {
            pb.directory(new java.io.File(workingDir));
        }

        // 1. Apply caller-supplied env overrides first
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        // 2. Apply isolation policy (filters pb.environment() in-place)
        policy.applyTo(pb.environment());

        // 3. Re-add granted caller-supplied keys the policy stripped. Grants only
        //    exempt explicitly named caller env entries — inherited vars stay governed
        //    by the policy.
        if (envGrants != null && env != null) {
            for (String key : envGrants) {
                String value = env.get(key);
                if (value != null) {
                    pb.environment().put(key, value);
                }
            }
        }

        return pb;
    }
}
