package dev.vatn.plugins.devenv.model;

/**
 * A detected local LLM inference engine.
 *
 * @param name      "ollama", "lmstudio", "llama.cpp", "vllm", "gpt4all", …
 * @param kind      SERVER / CLI / APP / LIBRARY
 * @param version   best-effort version ("" if unknown)
 * @param path      resolved binary or app path
 * @param source    PATH / HOMEBREW / APP / …
 * @param endpoint  loopback API endpoint if it serves one ("" otherwise)
 * @param running   true if the endpoint responded
 * @param apiType   OLLAMA / OPENAI_COMPAT / CUSTOM / NONE
 * @param gpuOffload whether GPU offload is available on this host (Metal/CUDA/ROCm)
 */
public record LlmEngineEntry(String name, EngineKind kind, String version, String path,
                             RuntimeSource source, String endpoint, boolean running,
                             LlmApiType apiType, boolean gpuOffload) {
}
