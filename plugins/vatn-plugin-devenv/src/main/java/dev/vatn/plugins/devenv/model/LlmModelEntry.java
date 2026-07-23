package dev.vatn.plugins.devenv.model;

/**
 * A locally-available model (weights on disk), discovered filesystem-first.
 *
 * @param id            "llama3.2:latest", "mlx-community/Qwen3-4B-Instruct-2507-4bit", filename…
 * @param family        llama / qwen / gemma / mistral / phi / … (best-effort)
 * @param parameters    "8B", "30B", "1.3B" if derivable ("")
 * @param quantization  "Q4_K_M", "q8_0", "4bit", "bf16" … ("")
 * @param format        GGUF / SAFETENSORS / MLX / OLLAMA / UNKNOWN
 * @param sizeBytes     on-disk size (-1 if unknown)
 * @param location      path or repo id
 * @param engine        owning store/engine ("ollama", "huggingface", "gguf", …)
 */
public record LlmModelEntry(String id, String family, String parameters, String quantization,
                            ModelFormat format, long sizeBytes, String location, String engine) {
}
