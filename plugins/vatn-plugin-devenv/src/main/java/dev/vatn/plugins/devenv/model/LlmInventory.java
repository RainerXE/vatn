package dev.vatn.plugins.devenv.model;

import java.util.List;

/** Local LLM engines + models, with live endpoints and total on-disk model size. */
public record LlmInventory(List<LlmEngineEntry> engines, List<LlmModelEntry> models,
                           List<String> runningEndpoints, long totalModelBytes) {
    public static LlmInventory empty() {
        return new LlmInventory(List.of(), List.of(), List.of(), 0L);
    }
}
