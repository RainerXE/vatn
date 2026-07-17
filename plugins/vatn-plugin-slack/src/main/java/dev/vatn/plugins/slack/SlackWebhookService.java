package dev.vatn.plugins.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Incoming-Webhook–based implementation of {@link SlackService}. */
public class SlackWebhookService implements SlackService {

    private static final Logger log = LoggerFactory.getLogger(SlackWebhookService.class);

    private final SlackConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SlackWebhookService(SlackConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
    }

    @Override
    public void notify(String text) throws Exception {
        String payload = mapper.writeValueAsString(Map.of("text", text));
        post(payload);
    }

    @Override
    public void notifyRaw(String jsonPayload) throws Exception {
        post(jsonPayload);
    }

    private void post(String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getWebhookUrl()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Slack webhook error " + response.statusCode()
                    + ": " + response.body());
        }
        log.debug("Slack notification sent — status {}", response.statusCode());
    }
}
