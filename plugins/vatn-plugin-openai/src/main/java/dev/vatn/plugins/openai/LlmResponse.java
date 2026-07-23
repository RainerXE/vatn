package dev.vatn.plugins.openai;

/** The model's reply to a prompt or conversation. */
public record LlmResponse(
        String text,
        String model,
        int inputTokens,
        int outputTokens
) {}
