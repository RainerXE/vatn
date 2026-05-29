package dev.vatn.api;

import java.util.List;
import java.util.Map;

/**
 * A loaded and instantiated WebAssembly module.
 *
 * <h3>Integer function calls</h3>
 * <pre>{@code
 * // Module exported: (func $add (param i32 i32) (result i32))
 * long[] result = module.call("add", 40L, 2L);
 * int sum = (int) result[0];   // 42
 * }</pre>
 *
 * <h3>WASI module execution</h3>
 * <pre>{@code
 * // Module is a WASI binary (compiled with wasm32-wasi target)
 * String output = module.callWasi(new String[]{"checker", "src/main.odin"}, null);
 * }</pre>
 *
 * <p>Modules run inside the WASM linear-memory sandbox — they cannot read or
 * write JVM heap memory outside their own allocation. WASI modules only see
 * the filesystem paths explicitly granted when they were loaded (defaulting to
 * the node workspace, read-only).
 */
@VatnApi(since = "1.0-alpha.11")
public interface VWasmModule {

    /** The unique identifier this module was loaded under. */
    String id();

    /**
     * Calls an exported function with integer arguments and returns integer results.
     *
     * <p>Both {@code i32} and {@code i64} WASM types are mapped to {@code long}.
     * {@code i32} return values are zero-extended to 64-bit.
     *
     * @param function the exported function name
     * @param args     zero or more integer arguments (i32 or i64)
     * @return results array; empty if the function returns nothing
     * @throws VWasmCallException if the function does not exist or the module traps
     */
    long[] call(String function, long... args);

    /**
     * Runs a WASI module as a command: passes {@code argv} and optional
     * environment variables, captures stdout, and returns it as a string.
     *
     * <p>The module's {@code _start} (or {@code main}) export is invoked.
     * stderr is captured separately and included in any thrown
     * {@link VWasmCallException} if the process exits non-zero.
     *
     * @param argv argv[0] is the program name, subsequent elements are arguments
     * @param env  additional environment variables (may be null/empty)
     * @return the captured stdout of the WASM process
     * @throws VWasmCallException if the WASM process exits with a non-zero code
     */
    String callWasi(String[] argv, Map<String, String> env);

    /**
     * Lists all exported function names from this module.
     */
    List<String> exports();

    /**
     * Returns true if this module has an exported function with the given name.
     */
    boolean hasExport(String function);

    /**
     * Releases the resources held by this module instance.
     * After calling this, the module is no longer usable.
     */
    void unload();
}
