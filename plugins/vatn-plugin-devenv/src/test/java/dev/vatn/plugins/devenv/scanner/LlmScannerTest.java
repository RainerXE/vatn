package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.EngineKind;
import dev.vatn.plugins.devenv.model.LlmApiType;
import dev.vatn.plugins.devenv.scanner.LlmScanner.EngineSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmScannerTest {

    @Test
    void derivesFamilyParamsQuantFromNames() {
        assertEquals("qwen", LlmScanner.deriveFamily("qwen2.5-coder:3b-base-q8_0"));
        assertEquals("llama", LlmScanner.deriveFamily("Meta-Llama-3-8B-Instruct.Q4_K_M.gguf"));
        assertEquals("", LlmScanner.deriveFamily("some-random-model"));

        assertEquals("8B", LlmScanner.deriveParams("Meta-Llama-3-8B-Instruct.Q4_K_M.gguf"));
        assertEquals("3B", LlmScanner.deriveParams("qwen2.5-coder:3b"));
        assertEquals("8x7B", LlmScanner.deriveParams("Mixtral-8x7B-Instruct"));

        assertEquals("Q4_K_M", LlmScanner.deriveQuant("Meta-Llama-3-8B-Instruct.Q4_K_M.gguf"));
        assertEquals("q8_0", LlmScanner.deriveQuant("qwen2.5-coder:3b-base-q8_0"));
        assertEquals("4bit", LlmScanner.deriveQuant("Qwen3-4B-Instruct-2507-4bit"));
        assertEquals("bf16", LlmScanner.deriveQuant("moshiko-mlx-bf16"));
    }

    @Test
    void detectsGgufMagic(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("m.gguf");
        Files.write(good, new byte[]{'G', 'G', 'U', 'F', 0, 0, 0, 3});
        Path bad = dir.resolve("x.gguf");
        Files.write(bad, new byte[]{'N', 'O', 'P', 'E'});
        assertTrue(LlmScanner.isGguf(good));
        assertFalse(LlmScanner.isGguf(bad));
    }

    @Test
    void parsesConfigEnginesAndModelDirs() {
        Map<String, Object> llm = Map.of(
                "engines", List.of(Map.of(
                        "name", "my-svc", "kind", "SERVER",
                        "binaries", List.of("mysvc"), "apps", List.of(),
                        "endpoint", "http://127.0.0.1:9000/v1", "apiType", "OPENAI_COMPAT")),
                "modelDirs", List.of("/data/models", "/srv/gguf"));

        List<EngineSpec> engines = LlmScanner.parseEngineSpecs(llm);
        assertEquals(1, engines.size());
        EngineSpec e = engines.get(0);
        assertEquals("my-svc", e.name());
        assertEquals(EngineKind.SERVER, e.kind());
        assertEquals(List.of("mysvc"), e.binaries());
        assertEquals(LlmApiType.OPENAI_COMPAT, e.apiType());

        List<Path> dirs = LlmScanner.parseModelDirs(llm);
        assertEquals(List.of(Path.of("/data/models"), Path.of("/srv/gguf")), dirs);
    }

    @Test
    void configDefaultsAndBadValuesAreLenient() {
        Map<String, Object> llm = Map.of("engines", List.of(
                Map.of("name", "x", "kind", "BOGUS", "apiType", "")));
        EngineSpec e = LlmScanner.parseEngineSpecs(llm).get(0);
        assertEquals(EngineKind.SERVER, e.kind());        // bad kind → default
        assertEquals(LlmApiType.OPENAI_COMPAT, e.apiType()); // blank → default
        assertTrue(LlmScanner.parseModelDirs(Map.of()).isEmpty());
    }
}
