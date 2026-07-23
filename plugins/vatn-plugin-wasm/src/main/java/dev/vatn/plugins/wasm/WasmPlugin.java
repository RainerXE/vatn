package dev.vatn.plugins.wasm;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VWasmRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Drop-in WASM execution plugin for VATN.
 *
 * <p>Registers a {@link VWasmRuntime} backed by Chicory (pure-Java, zero-JNI) in the
 * node context. Any plugin can then load and execute {@code .wasm} modules without
 * declaring a compile-time dependency on the WASM runtime itself.
 *
 * <p>Default config loads all {@code .wasm} files from {@code .vatn/wasm/} at startup
 * and exposes a management REST API at {@code /wasm}.
 *
 * <pre>{@code
 * // In any other plugin:
 * VWasmRuntime wasm = ctx.getService(VWasmRuntime.class).orElseThrow();
 *
 * // Load a .wasm module
 * byte[] bytes = Files.readAllBytes(ctx.getWorkspacePath().resolve(".vatn/wasm/verify.wasm"));
 * VWasmModule mod = wasm.load("verifier", bytes);
 *
 * // Call an exported function
 * long[] result = mod.call("add", 40L, 2L);   // → [42]
 *
 * // Run a WASI binary (captures stdout)
 * String output = mod.callWasi(new String[]{"verify", "src/main.odin"}, null);
 * }</pre>
 *
 * <h3>Upgrading Chicory</h3>
 * The Chicory version is pinned in {@code vatn-plugins-parent/pom.xml} as
 * {@code <chicory.version>}. Change that one property and rebuild.
 *
 * <h3>Switching to GraalWASM</h3>
 * Replace the {@code new ChicoryWasmRuntime(...)} line in {@link #onInitialize}
 * with a {@code GraalWasmRuntime} instance that implements the same
 * {@link VWasmRuntime} SPI. All callers continue working unchanged.
 */
public class WasmPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(WasmPlugin.class);

    private final WasmConfig       config;
    private ChicoryWasmRuntime     runtime;

    /** Default constructor — uses sensible defaults. */
    public WasmPlugin() {
        this(WasmConfig.builder().build());
    }

    public WasmPlugin(WasmConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.wasm"; }
    @Override public String getName()    { return "VATN WASM Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.15"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Initializing VATN WASM Plugin (engine={} {})",
            ChicoryWasmRuntime.NAME, ChicoryWasmRuntime.VERSION);

        Path workspacePath = ctx.getWorkspacePath();
        Path moduleDir     = config.moduleDir() != null
            ? config.moduleDir()
            : workspacePath.resolve(".vatn/wasm");

        // ── Wire runtime ─────────────────────────────────────────────────────
        // To swap to GraalWASM: replace the next line with:
        //   runtime = new GraalWasmRuntime(config, workspacePath);
        // Everything else remains the same.
        runtime = new ChicoryWasmRuntime(config, workspacePath);

        ctx.registerService(VWasmRuntime.class, runtime);
        ctx.register("/wasm", new WasmHttpService(runtime));
        ctx.registerHealthCheck("wasm", () -> true);

        // ── Auto-load .wasm files from moduleDir ─────────────────────────────
        if (config.autoLoadDir() && Files.isDirectory(moduleDir)) {
            autoLoadDirectory(moduleDir);
        } else if (config.autoLoadDir()) {
            log.info("[WASM] Module dir '{}' does not exist yet — no modules auto-loaded. "
                + "Create the directory and add .wasm files, or call POST /wasm/modules/{id}/load.",
                moduleDir);
        }

        log.info("[WASM] Plugin ready — {} module(s) loaded, REST API at /wasm",
            runtime.listModules().size());
    }

    @Override
    public void onShutdown() {
        if (runtime != null) {
            log.info("[WASM] Unloading {} module(s)", runtime.listModules().size());
            runtime.unloadAll();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void autoLoadDirectory(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".wasm"))
                 .sorted()
                 .forEach(p -> {
                     String id = p.getFileName().toString().replace(".wasm", "");
                     try {
                         runtime.load(id, Files.readAllBytes(p));
                         log.info("[WASM] Auto-loaded '{}' from {}", id, p.getFileName());
                     } catch (Exception e) {
                         log.warn("[WASM] Failed to auto-load '{}': {}", p.getFileName(), e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.warn("[WASM] Could not scan module dir '{}': {}", dir, e.getMessage());
        }
    }
}
