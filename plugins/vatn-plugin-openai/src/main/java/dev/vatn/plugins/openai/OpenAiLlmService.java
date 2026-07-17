package dev.vatn.plugins.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** HTTP-based {@link LlmService} implementation for OpenAI and Anthropic APIs. */
public class OpenAiLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmService.class);

    private final OpenAiConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiLlmService(OpenAiConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
    }

    @Override
    public LlmResponse complete(String prompt) throws Exception {
        return chat(List.of(LlmMessage.user(prompt)));
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages) throws Exception {
        return switch (config.getProvider()) {
            case OPENAI    -> callOpenAi(messages);
            case ANTHROPIC -> callAnthropic(messages);
        };
    }

    // -------------------------------------------------------------------------

    private LlmResponse callOpenAi(List<LlmMessage> messages) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());

        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage m : messages) {
            msgs.addObject().put("role", m.role()).put("content", m.content());
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        JsonNode resp = send(req);
        String text = resp.at("/choices/0/message/content").asText();
        String model = resp.path("model").asText(config.getModel());
        int inputTokens  = resp.at("/usage/prompt_tokens").asInt();
        int outputTokens = resp.at("/usage/completion_tokens").asInt();
        return new LlmResponse(text, model, inputTokens, outputTokens);
    }

    private LlmResponse callAnthropic(List<LlmMessage> messages) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());

        // Anthropic puts system messages in a separate top-level field
        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage m : messages) {
            if ("system".equals(m.role())) {
                body.put("system", m.content());
            } else {
                msgs.addObject().put("role", m.role()).put("content", m.content());
            }
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + "/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", config.getAnthropicVersion())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        JsonNode resp = send(req);
        String text = resp.at("/content/0/text").asText();
        String model = resp.path("model").asText(config.getModel());
        int inputTokens  = resp.at("/usage/input_tokens").asInt();
        int outputTokens = resp.at("/usage/output_tokens").asInt();
        return new LlmResponse(text, model, inputTokens, outputTokens);
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.debug("LLM response status: {}", response.statusCode());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("LLM API error " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }
}
