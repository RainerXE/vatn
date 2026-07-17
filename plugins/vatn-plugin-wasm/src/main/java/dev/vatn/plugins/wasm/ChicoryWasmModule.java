package dev.vatn.plugins.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import dev.vatn.api.VWasmCallException;
import dev.vatn.api.VWasmModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chicory-backed {@link VWasmModule}.
 *
 * <p>Verified against Chicory 1.7.5 API:
 * <ul>
 *   <li>{@code ExportFunction.apply(long...)} → {@code long[]} (no Value wrappers needed)</li>
 *   <li>{@code Store.instantiate(name, WasmModule)} → {@code Instance}</li>
 *   <li>{@code WasiPreview1.toHostFunctions()} → {@code HostFunction[]}</li>
 *   <li>{@code WasmModule.exportSection().getExport(i).name()} for export listing</li>
 * </ul>
 *
 * <p>Each call creates a fresh {@link Instance} to prevent state leakage between
 * invocations — the safe default. To share state across calls, hold the instance
 * externally and pass it in.
 */
class ChicoryWasmModule implements VWasmModule {

    private static final Logger log = LoggerFactory.getLogger(ChicoryWasmModule.class);

    private final String     moduleId;
    private final WasmModule parsed;
    private final Path       workspacePath;
    private final WasmConfig config;
    private volatile boolean unloaded = false;

    ChicoryWasmModule(String moduleId, WasmModule parsed, Path workspacePath, WasmConfig config) {
        this.moduleId      = moduleId;
        this.parsed        = parsed;
        this.workspacePath = workspacePath;
        this.config        = config;
    }

    @Override
    public String id() { return moduleId; }

    // ── Integer function call ─────────────────────────────────────────────────

    @Override
    public long[] call(String function, long... args) {
        checkNotUnloaded();
        try {
            Instance instance = new Store().instantiate(moduleId, parsed);
            ExportFunction fn = instance.export(function);
            if (fn == null) throw new VWasmCallException(
                "Function '" + function + "' not found in module '" + moduleId + "'");

            return fn.apply(args);  // Chicory 1.x: apply(long...) → long[] directly

        } catch (VWasmCallException e) {
            throw e;
        } catch (Exception e) {
            throw new VWasmCallException(
                "WASM trap in '" + function + "' (module='" + moduleId + "'): " + e.getMessage(), e);
        }
    }

    // ── WASI execution ────────────────────────────────────────────────────────

    @Override
    public String callWasi(String[] argv, Map<String, String> env) {
        checkNotUnloaded();

        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();

        try (WasiPreview1 wasi = buildWasi(argv, env, stdoutBuf, stderrBuf)) {
            HostFunction[] hostFns = wasi.toHostFunctions();
            Instance instance = new Store().addFunction(hostFns).instantiate(moduleId, parsed);

            try {
                ExportFunction start = instance.export("_start");
                if (start == null) start = instance.export("main");
                if (start == null) throw new VWasmCallException(
                    "WASI module '" + moduleId + "' has no '_start' or 'main' export");
                start.apply();
            } catch (Exception e) {
                if (!isCleanWasiExit(e)) {
                    int code = extractExitCode(e);
                    String stderr = stderrBuf.toString(StandardCharsets.UTF_8);
                    throw new VWasmCallException(
                        "WASI module '" + moduleId + "' exited " + code
                        + (stderr.isBlank() ? "" : "\nstderr: " + stderr.trim()), code);
                }
            }

            return stdoutBuf.toString(StandardCharsets.UTF_8);

        } catch (VWasmCallException e) {
            throw e;
        } catch (Exception e) {
            throw new VWasmCallException(
                "WASI execution failed for '" + moduleId + "': " + e.getMessage(), e);
        }
    }

    // ── Introspection ─────────────────────────────────────────────────────────

    @Override
    public List<String> exports() {
        checkNotUnloaded();
        var section = parsed.exportSection();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < section.exportCount(); i++) {
            var export = section.getExport(i);
            if (export.exportType() == ExternalType.FUNCTION) {
                names.add(export.name());
            }
        }
        return names;
    }

    @Override
    public boolean hasExport(String function) {
        return exports().contains(function);
    }

    @Override
    public void unload() {
        unloaded = true;
        log.debug("[WASM] Module '{}' unloaded", moduleId);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void checkNotUnloaded() {
        if (unloaded) throw new IllegalStateException(
            "WASM module '" + moduleId + "' has been unloaded");
    }

    private WasiPreview1 buildWasi(String[] argv,
                                   Map<String, String> callerEnv,
                                   ByteArrayOutputStream stdout,
                                   ByteArrayOutputStream stderr) {
        WasiOptions.Builder opts = WasiOptions.builder()
            .withStdout(stdout)
            .withStderr(stderr)
            .withArguments(argv != null ? List.of(argv) : List.of("wasm"))
            .withThrowOnExit0(false); // let us capture stdout before exit propagates

        // Scope filesystem to workspace path
        if (workspacePath != null && Files.exists(workspacePath)) {
            opts.withDirectory("/workspace", workspacePath);
            opts.withDirectory("/", workspacePath);
        }

        // Only forward explicitly approved env vars from the host process
        for (String key : config.allowedEnvVars()) {
            String val = System.getenv(key);
            if (val != null) opts.withEnvironment(key, val);
        }
        if (callerEnv != null) callerEnv.forEach(opts::withEnvironment);

        return WasiPreview1.builder().withOptions(opts.build()).build();
    }

    private static boolean isCleanWasiExit(Exception e) {
        String name = e.getClass().getSimpleName();
        return (name.contains("WasiExit") || name.contains("ExitException"))
            && extractExitCode(e) == 0;
    }

    private static int extractExitCode(Exception e) {
        for (String fieldName : new String[]{"exitCode", "code", "status"}) {
            try {
                var f = e.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                return ((Number) f.get(e)).intValue();
            } catch (Exception ignored) {}
        }
        return -1;
    }
}
