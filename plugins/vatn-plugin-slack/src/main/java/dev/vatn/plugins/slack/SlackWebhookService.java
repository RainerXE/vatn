package dev.vatn.plugins.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Incoming-Webhook–based implementation of {@link SlackService} with retry and rate-limit handling. */
public class SlackWebhookService implements SlackService {

    private static final Logger log = LoggerFactory.getLogger(SlackWebhookService.class);
    private static final long[] BACKOFF_MS = {1000L, 2000L, 4000L};

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
        postWithRetry(payload);
    }

    @Override
    public void notifyRaw(String jsonPayload) throws Exception {
        postWithRetry(jsonPayload);
    }

    private void postWithRetry(String payload) throws Exception {
        for (int attempt = 0; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> response = send(payload);
                int code = response.statusCode();

                if (code >= 200 && code < 300) {
                    log.debug("Slack notification sent — status {}", code);
                    return;
                }

                if (code == 429) {
                    handleRateLimit(response, payload);
                    return;
                }

                if (code >= 400 && code < 500) {
                    throw new RuntimeException("Slack webhook error " + code + ": " + response.body());
                }

                if (attempt == 3) {
                    throw new RuntimeException("Slack webhook error " + code + ": " + response.body());
                }

                log.warn("Slack webhook {} (attempt {}), retrying in {}ms", code, attempt + 1, BACKOFF_MS[attempt]);
                Thread.sleep(BACKOFF_MS[attempt]);

            } catch (IOException e) {
                if (attempt == 3) throw e;
                log.warn("Slack webhook network error (attempt {}), retrying in {}ms: {}",
                        attempt + 1, BACKOFF_MS[attempt], e.getMessage());
                Thread.sleep(BACKOFF_MS[attempt]);
            }
        }
    }

    private void handleRateLimit(HttpResponse<String> response, String payload) throws Exception {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("1");
        long waitMs = parseRetryAfter(retryAfter);
        log.warn("Slack rate-limited (429), waiting {}ms before retry", waitMs);
        Thread.sleep(waitMs);

        HttpResponse<String> retryResp = send(payload);
        if (retryResp.statusCode() >= 200 && retryResp.statusCode() < 300) {
            log.debug("Slack notification sent — status {}", retryResp.statusCode());
            return;
        }
        throw new RuntimeException("Slack webhook error " + retryResp.statusCode() + ": " + retryResp.body());
    }

    private HttpResponse<String> send(String payload) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getWebhookUrl()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static long parseRetryAfter(String value) {
        try {
            return Long.parseLong(value) * 1000L + 1000L;
        } catch (NumberFormatException e) {
            try {
                Instant retryInstant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
                long diff = Duration.between(Instant.now(), retryInstant).toMillis();
                return Math.max(diff, 0) + 1000L;
            } catch (DateTimeParseException ex) {
                return 5000L;
            }
        }
    }
}
