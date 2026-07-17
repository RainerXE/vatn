package dev.vatn.plugins.openai;

/**
 * Configuration for the LLM client plugin.
 *
 * <p>Supports OpenAI (GPT-4o, o1, …) and Anthropic (Claude) out of the box.
 * For any OpenAI-compatible endpoint (Ollama, Azure OpenAI, etc.) use
 * {@link #openai(String)} and override the base URL with {@link #withBaseUrl(String)}.
 *
 * <pre>{@code
 * // OpenAI
 * OpenAiConfig config = OpenAiConfig.openai("sk-…").withModel("gpt-4o");
 *
 * // Anthropic / Claude
 * OpenAiConfig config = OpenAiConfig.anthropic("sk-ant-…");
 * }</pre>
 */
public final class OpenAiConfig {

    public enum Provider { OPENAI, ANTHROPIC }

    private static final String OPENAI_BASE_URL    = "https://api.openai.com/v1";
    private static final String ANTHROPIC_BASE_URL  = "https://api.anthropic.com/v1";
    private static final String ANTHROPIC_VERSION   = "2023-06-01";
    private static final String DEFAULT_OPENAI_MODEL     = "gpt-4o";
    private static final String DEFAULT_ANTHROPIC_MODEL  = "claude-opus-4-7";

    private final Provider provider;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int maxTokens;
    private final int timeoutSeconds;

    private OpenAiConfig(Provider provider, String apiKey, String model, String baseUrl,
                         int maxTokens, int timeoutSeconds) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static OpenAiConfig openai(String apiKey) {
        return new OpenAiConfig(Provider.OPENAI, apiKey, DEFAULT_OPENAI_MODEL,
                OPENAI_BASE_URL, 1024, 30);
    }

    public static OpenAiConfig anthropic(String apiKey) {
        return new OpenAiConfig(Provider.ANTHROPIC, apiKey, DEFAULT_ANTHROPIC_MODEL,
                ANTHROPIC_BASE_URL, 1024, 30);
    }

    public OpenAiConfig withModel(String model) {
        return new OpenAiConfig(provider, apiKey, model, baseUrl, maxTokens, timeoutSeconds);
    }

    public OpenAiConfig withBaseUrl(String baseUrl) {
        return new OpenAiConfig(provider, apiKey, model, baseUrl, maxTokens, timeoutSeconds);
    }

    public OpenAiConfig withMaxTokens(int maxTokens) {
        return new OpenAiConfig(provider, apiKey, model, baseUrl, maxTokens, timeoutSeconds);
    }

    public OpenAiConfig withTimeoutSeconds(int timeoutSeconds) {
        return new OpenAiConfig(provider, apiKey, model, baseUrl, maxTokens, timeoutSeconds);
    }

    public Provider getProvider()       { return provider; }
    public String getApiKey()           { return apiKey; }
    public String getModel()            { return model; }
    public String getBaseUrl()          { return baseUrl; }
    public int getMaxTokens()           { return maxTokens; }
    public int getTimeoutSeconds()      { return timeoutSeconds; }
    public String getAnthropicVersion() { return ANTHROPIC_VERSION; }
}
