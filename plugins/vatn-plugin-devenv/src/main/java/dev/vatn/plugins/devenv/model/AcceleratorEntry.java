package dev.vatn.plugins.devenv.model;

/**
 * A compute accelerator (GPU) available for ML/LLM workloads.
 *
 * @param vendor      "NVIDIA" | "AMD" | "Apple"
 * @param name        device name (e.g. "NVIDIA GeForce RTX 4090", "Apple M3 Max GPU")
 * @param memoryBytes device/unified memory in bytes (-1 if unknown)
 * @param driver      driver/runtime version ("" if unknown)
 * @param apiType     "CUDA" | "ROCM" | "METAL"
 */
public record AcceleratorEntry(String vendor, String name, long memoryBytes, String driver, String apiType) {
}
