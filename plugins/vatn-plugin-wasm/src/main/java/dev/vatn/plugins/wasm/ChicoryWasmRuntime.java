package dev.vatn.plugins.wasm;

import com.dylibso.chicory.wasm.Parser;
import dev.vatn.api.VWasmModule;
import dev.vatn.api.VWasmRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chicory-backed implementation of {@link VWasmRuntime}.
 *
 * <h3>Swapping to GraalWASM</h3>
 * Replace this class with {@code GraalWasmRuntime} (implements the same
 * {@link VWasmRuntime} SPI) and register it in {@link WasmPlugin#onInitialize}.
 * No callers need to change — they all depend on the SPI interface, not this class.
 *
 * <p>Minimal GraalWASM sketch (implement when needed):
 * <pre>{@code
 * class GraalWasmRuntime implements VWasmRuntime {
 *     // org.graalvm.polyglot.Context + Source.newBuilder("wasm", bytes, id)
 *     // context.eval(source) → Value (wasm module)
 *     // value.getMember("exports").getMember(fn).execute(args)
 * }
 * }</pre>
 */
class ChicoryWasmRuntime implements VWasmRuntime {

    private static final Logger log = LoggerFactory.getLogger(ChicoryWasmRuntime.class);

    static final String NAME    = "chicory";
    static final String VERSION = "1.7.5";

    private final WasmConfig config;
    private final Path       workspacePath;
    private final Map<String, ChicoryWasmModule> modules = new ConcurrentHashMap<>();

    ChicoryWasmRuntime(WasmConfig config, Path workspacePath) {
        this.config        = config;
        this.workspacePath = workspacePath;
    }

    // ── VWasmRuntime ─────────────────────────────────────────────────────────

    @Override
    public VWasmModule load(String moduleId, byte[] wasmBytes) {
        if (modules.containsKey(moduleId)) throw new IllegalStateException(
            "Module '" + moduleId + "' is already loaded. Call unload() first.");

        log.info("[WASM] Loading module '{}' ({} bytes, engine={})", moduleId, wasmBytes.length, NAME);
        var parsed = Parser.parse(wasmBytes);
        var module = new ChicoryWasmModule(moduleId, parsed, workspacePath, config);
        modules.put(moduleId, module);
        log.info("[WASM] Module '{}' loaded. Exports: {}", moduleId, module.exports());
        return module;
    }

    @Override
    public VWasmModule load(String moduleId, InputStream wasmStream) throws IOException {
        return load(moduleId, wasmStream.readAllBytes());
    }

    @Override
    public Optional<VWasmModule> get(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    @Override
    public List<String> listModules() {
        return new ArrayList<>(modules.keySet());
    }

    @Override
    public void unload(String moduleId) {
        ChicoryWasmModule m = modules.remove(moduleId);
        if (m != null) {
            m.unload();
            log.info("[WASM] Module '{}' unloaded", moduleId);
        }
    }

    @Override
    public String runtimeName()    { return NAME; }

    @Override
    public String runtimeVersion() { return VERSION; }

    void unloadAll() {
        modules.forEach((id, m) -> m.unload());
        modules.clear();
    }
}
