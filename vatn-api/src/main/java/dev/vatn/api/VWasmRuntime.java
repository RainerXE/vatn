package dev.vatn.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * SPI for loading and executing WebAssembly modules inside a VATN node.
 *
 * <p>The default implementation in {@code vatn-plugin-wasm} uses
 * <a href="https://github.com/dylibso/chicory">Chicory</a> — a pure-Java,
 * zero-JNI WASM runtime. An optional high-performance backend using
 * GraalWASM can be registered when running on a GraalVM JDK.
 *
 * <p>Usage:
 * <pre>{@code
 * VWasmRuntime wasm = ctx.getService(VWasmRuntime.class).orElseThrow();
 *
 * // Load a module from classpath or disk
 * byte[] bytes = Files.readAllBytes(ctx.getWorkspacePath().resolve(".vatn/wasm/verifier.wasm"));
 * VWasmModule module = wasm.load("verifier", bytes);
 *
 * // Call an exported integer function
 * long[] result = module.call("add", 40L, 2L);   // → [42]
 *
 * // Run a WASI module (stdin/stdout/fs-scoped)
 * String output = module.callWasi(new String[]{"odin", "check", "."}, null);
 * }</pre>
 *
 * <p>Modules are scoped to the WASM heap — they cannot access JVM memory
 * outside their linear memory, fulfilling the JVM-level sandbox guarantee.
 * File-system access is capability-gated: WASI modules only see paths
 * explicitly granted at load time (default: the node workspace path, read-only).
 *
 * @see VWasmModule
 */
@VatnApi(since = "1.0-alpha.11")
public interface VWasmRuntime extends VService {

    /**
     * Loads a WASM module from raw bytes and makes it available under {@code moduleId}.
     *
     * @param moduleId unique identifier for this module (used in subsequent {@link #get} calls)
     * @param wasmBytes raw {@code .wasm} binary content
     * @return an instantiated module ready to call
     * @throws IllegalArgumentException if the bytes are not a valid WASM module
     * @throws IllegalStateException if {@code moduleId} is already loaded (call {@link #unload} first)
     */
    VWasmModule load(String moduleId, byte[] wasmBytes);

    /**
     * Loads a WASM module from an {@link InputStream}.
     *
     * @param moduleId unique identifier for this module
     * @param wasmStream stream whose content is the raw {@code .wasm} binary
     * @return an instantiated module ready to call
     * @throws IOException if the stream cannot be read
     */
    VWasmModule load(String moduleId, InputStream wasmStream) throws IOException;

    /**
     * Returns a previously loaded module, or empty if not found.
     */
    Optional<VWasmModule> get(String moduleId);

    /**
     * Returns the IDs of all currently loaded modules.
     */
    List<String> listModules();

    /**
     * Unloads a module and releases its resources.
     * No-op if the module is not loaded.
     */
    void unload(String moduleId);

    /**
     * Returns a short identifier for the underlying WASM engine,
     * e.g. {@code "chicory"} or {@code "graalwasm"}.
     */
    String runtimeName();

    /**
     * Returns the version string of the underlying WASM engine,
     * e.g. {@code "1.7.5"}.
     */
    String runtimeVersion();
}
