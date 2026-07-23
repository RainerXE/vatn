package dev.vatn.plugins.openai;

import dev.vatn.api.VService;

import java.util.List;

/**
 * LLM client service registered in the VATN node context by {@link OpenAiPlugin}.
 *
 * <pre>{@code
 * LlmService llm = ctx.getService(LlmService.class).orElseThrow();
 *
 * // Single-turn prompt
 * LlmResponse reply = llm.complete("Summarise this text: " + body);
 *
 * // Multi-turn chat
 * LlmResponse reply = llm.chat(List.of(
 *     LlmMessage.system("You are a helpful assistant."),
 *     LlmMessage.user("What is the capital of France?")
 * ));
 * }</pre>
 */
public interface LlmService extends VService {

    /** Sends a single user prompt and returns the model's reply. */
    LlmResponse complete(String prompt) throws Exception;

    /** Sends a multi-turn conversation and returns the model's next reply. */
    LlmResponse chat(List<LlmMessage> messages) throws Exception;
}
