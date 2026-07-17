package dev.vatn.plugins.wasm;

import dev.vatn.api.VWasmCallException;
import dev.vatn.api.VWasmModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness tests for ChicoryWasmRuntime + ChicoryWasmModule.
 * Uses the same embedded WASM binary as WasmRuntimeBenchmark.
 */
class WasmRuntimeTest {

    private ChicoryWasmRuntime runtime;

    @BeforeEach
    void setUp() {
        WasmConfig config = WasmConfig.builder().autoLoadDir(false).build();
        runtime = new ChicoryWasmRuntime(config, Path.of(System.getProperty("user.dir")));
    }

    @AfterEach
    void tearDown() {
        runtime.unloadAll();
    }

    @Test
    void load_producesModuleWithExpectedExport() {
        VWasmModule mod = runtime.load("math", WasmRuntimeBenchmark.ADD_WASM);
        assertTrue(mod.hasExport("add"), "module must export 'add'");
        assertEquals(List.of("add"), mod.exports());
    }

    @Test
    void call_add_correctResult() {
        VWasmModule mod = runtime.load("math", WasmRuntimeBenchmark.ADD_WASM);
        long[] result = mod.call("add", 40L, 2L);
        assertEquals(1, result.length);
        assertEquals(42L, result[0]);
    }

    @Test
    void call_add_largeValues() {
        VWasmModule mod = runtime.load("math", WasmRuntimeBenchmark.ADD_WASM);
        long a = 1_000_000_000L, b = 999_000_000L;
        assertEquals(a + b, mod.call("add", a, b)[0]);
    }

    @Test
    void call_unknownFunction_throwsCallException() {
        VWasmModule mod = runtime.load("math", WasmRuntimeBenchmark.ADD_WASM);
        assertThrows(VWasmCallException.class, () -> mod.call("nonexistent", 1L));
    }

    @Test
    void runtimeName_isChicory() {
        assertEquals("chicory", runtime.runtimeName());
    }

    @Test
    void listModules_reflectsLoadedModules() {
        runtime.load("a", WasmRuntimeBenchmark.ADD_WASM);
        runtime.load("b", WasmRuntimeBenchmark.ADD_WASM);
        List<String> ids = runtime.listModules();
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
        assertEquals(2, ids.size());
    }

    @Test
    void unload_removesModule() {
        runtime.load("tmp", WasmRuntimeBenchmark.ADD_WASM);
        assertTrue(runtime.get("tmp").isPresent());
        runtime.unload("tmp");
        assertTrue(runtime.get("tmp").isEmpty());
    }

    @Test
    void loadDuplicate_throwsIllegalState() {
        runtime.load("dup", WasmRuntimeBenchmark.ADD_WASM);
        assertThrows(IllegalStateException.class,
            () -> runtime.load("dup", WasmRuntimeBenchmark.ADD_WASM));
    }

    @Test
    void loadFromStream_works() throws Exception {
        var stream = new java.io.ByteArrayInputStream(WasmRuntimeBenchmark.ADD_WASM);
        VWasmModule mod = runtime.load("stream", stream);
        assertEquals(42L, mod.call("add", 40L, 2L)[0]);
    }

    @Test
    void unloadedModule_throwsOnCall() {
        VWasmModule mod = runtime.load("gone", WasmRuntimeBenchmark.ADD_WASM);
        runtime.unload("gone");
        assertThrows(IllegalStateException.class, () -> mod.call("add", 1L));
    }
}
