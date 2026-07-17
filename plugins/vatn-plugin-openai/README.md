# vatn-plugin-openai

Provides LLM completion and chat via any OpenAI-compatible API endpoint.

## How it works

Uses VATN's built-in `VHttpClient` to POST JSON to the `/v1/chat/completions` endpoint in the standard OpenAI format. Because no vendor SDK is involved, the same plugin works with OpenAI, Anthropic (via their compatibility layer), Ollama, and any other provider that speaks the same protocol. Model, token limit, and temperature are all configurable per plugin instance.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-openai</artifactId>
    <version>1.0-alpha.12</version>
</dependency>
```

```java
// OpenAI
VNodeRunner.create()
    .addPlugin(new OpenAiPlugin(
        OpenAiConfig.openai(System.getenv("OPENAI_API_KEY"))
            .withModel("gpt-4o")
            .withMaxTokens(1024)
            .withTemperature(0.7)))
    .run();

// Local Ollama
VNodeRunner.create()
    .addPlugin(new OpenAiPlugin(
        OpenAiConfig.ollama("http://localhost:11434")
            .withModel("llama3")))
    .run();
```

## API

```java
public interface LlmService {
    LlmResponse complete(String prompt);
    LlmResponse chat(List<LlmMessage> messages);
}

// LlmMessage(String role, String content)  — role: "system" | "user" | "assistant"
// LlmResponse(String content, String model, int promptTokens, int completionTokens)
```

```java
LlmService llm = ctx.service(LlmService.class);

// Single-turn completion
LlmResponse r = llm.complete("Summarise the VATN framework in one sentence.");
System.out.println(r.content());

// Multi-turn chat
List<LlmMessage> messages = List.of(
    new LlmMessage("system", "You are a helpful assistant."),
    new LlmMessage("user", "What is BM25?")
);
LlmResponse chat = llm.chat(messages);
System.out.printf("Used %d tokens%n", chat.promptTokens() + chat.completionTokens());
```

## Configuration

| Option        | Default                          | Meaning                                               |
|---------------|----------------------------------|-------------------------------------------------------|
| `baseUrl`     | `https://api.openai.com`         | API base URL (override for Anthropic, Ollama, etc.)   |
| `apiKey`      | —                                | Bearer token sent in `Authorization` header           |
| `model`       | provider default                 | Model identifier (e.g. `gpt-4o`, `llama3`)            |
| `maxTokens`   | provider default                 | Maximum tokens in the completion                      |
| `temperature` | provider default                 | Sampling temperature (0.0 = deterministic)            |

## Notes

- `OpenAiConfig.anthropic(apiKey)` points to `https://api.anthropic.com` with Anthropic's `x-api-key` header; not all Anthropic models expose every parameter.
- `OpenAiConfig.ollama(baseUrl)` sets an empty API key and no auth header.
- `complete(prompt)` is a convenience wrapper that wraps the prompt in a single `user` message.
- Token counts in `LlmResponse` reflect what the provider returns; some providers return 0 or omit usage fields.
