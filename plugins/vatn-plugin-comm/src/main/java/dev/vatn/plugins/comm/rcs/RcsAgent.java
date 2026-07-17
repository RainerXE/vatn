package dev.vatn.plugins.comm.rcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VAgent;
import dev.vatn.api.VAgentContext;
import dev.vatn.api.VHttpClient;
import dev.vatn.plugins.comm.CommChannel;
import dev.vatn.plugins.comm.CommBus;
import dev.vatn.plugins.comm.InboundMessage;
import dev.vatn.plugins.comm.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * RCS channel agent using a provider-agnostic webhook/REST model.
 *
 * <p><b>Inbound</b>: Registers a VATN HTTP route at {@link RcsConfig#getWebhookPath()}.
 * The provider must be configured to POST events to
 * {@code https://your-node.example.com{webhookPath}}.
 * Messages are only processed when this agent is PRIMARY (standby nodes return 200 immediately).
 *
 * <p><b>Outbound</b>: Supports Twilio, Sinch, MessageBird, and custom REST endpoints.
 * The send path is provider-specific and handled by {@link #sendViaProvider}.
 */
public class RcsAgent implements VAgent {

    private static final Logger log = LoggerFactory.getLogger(RcsAgent.class);

    private final RcsConfig config;
    private final CommBus service;
    private final ObjectMapper mapper = new ObjectMapper();

    private VAgentContext ctx;

    public RcsAgent(RcsConfig config, CommBus service) {
        this.config  = config;
        this.service = service;
    }

    @Override public String getId()          { return "comm.rcs"; }
    @Override public String getChannelType() { return "rcs"; }

    @Override
    public void onStart(VAgentContext ctx) {
        this.ctx = ctx;
        service.registerSender(CommChannel.RCS, this::sendMessage);
        registerWebhookRoute(ctx);
    }

    @Override
    public void onStop() {}

    // ── webhook ───────────────────────────────────────────────────────────────

    private void registerWebhookRoute(VAgentContext ctx) {
        String path = config.getWebhookPath();
        ctx.nodeContext().register(path, routes ->
            routes.post("", (req, res) -> {
                // Webhook is always registered but only the PRIMARY processes messages.
                // Standby returns 200 so the provider doesn't retry on the wrong node.
                if (!ctx.isPrimary()) { res.status(200).send("standby"); return; }

                if (config.getWebhookSecret() != null) {
                    String sig = req.getHeader("X-Webhook-Signature");
                    if (!config.getWebhookSecret().equals(sig)) {
                        res.status(403).send("forbidden");
                        return;
                    }
                }
                try {
                    JsonNode payload = mapper.readTree(req.getBody());
                    handleWebhookPayload(payload, req.getBody());
                } catch (Exception e) {
                    log.warn("RCS webhook parse error: {}", e.getMessage());
                }
                res.status(200).send("ok");
            })
        );
        log.info("RCS webhook route registered at {} (provider={})", path, config.getProvider());
    }

    private void handleWebhookPayload(JsonNode payload, String raw) {
        // Normalize across providers into InboundMessage
        String from = null;
        String text = null;
        String mediaUrl = null;

        switch (config.getProvider()) {
            case TWILIO -> {
                from    = payload.path("From").asText(payload.path("from").asText("unknown"));
                text    = payload.path("Body").asText(payload.path("body").asText(""));
                mediaUrl = payload.has("MediaUrl0") ? payload.path("MediaUrl0").asText() : null;
            }
            case SINCH -> {
                JsonNode msg = payload.path("message");
                from    = payload.path("from").asText("unknown");
                text    = msg.path("text").path("text").asText(msg.path("text").asText(""));
                if (msg.has("file")) mediaUrl = msg.path("file").path("url").asText();
            }
            case MESSAGEBIRD -> {
                from    = payload.path("originator").asText("unknown");
                text    = payload.path("body").asText("");
            }
            case GOOGLE_RBM -> {
                JsonNode event = payload.path("message");
                from    = payload.path("senderPhoneNumber").asText("unknown");
                text    = event.path("text").asText("");
                if (event.has("userFile")) mediaUrl = event.path("userFile").path("fileUri").asText();
            }
            default -> {
                // Best-effort for CUSTOM providers
                from = payload.path("from").asText(
                    payload.path("sender").asText(
                    payload.path("msisdn").asText("unknown")));
                text = payload.path("text").asText(
                    payload.path("body").asText(
                    payload.path("message").asText("")));
            }
        }

        if (from == null || (text.isBlank() && mediaUrl == null)) return;

        service.dispatch(new InboundMessage(
            CommChannel.RCS, from, text, mediaUrl, raw, java.time.Instant.now()));
    }

    // ── send ──────────────────────────────────────────────────────────────────

    private void sendMessage(OutboundMessage msg) {
        VHttpClient http = ctx.nodeContext().getService(VHttpClient.class).orElseThrow();
        try {
            sendViaProvider(http, msg);
        } catch (Exception e) {
            log.error("RCS send error to {}", msg.to(), e);
            throw new RuntimeException("RCS send failed", e);
        }
    }

    private void sendViaProvider(VHttpClient http, OutboundMessage msg) throws Exception {
        switch (config.getProvider()) {
            case TWILIO -> {
                // Twilio uses form-encoded POST with Basic auth
                String body = "To=" + encode(msg.to())
                    + "&From=" + encode(config.getFromNumber())
                    + "&Body=" + encode(msg.text());
                String auth = Base64.getEncoder().encodeToString(
                    (config.getApiKey() + ":" + config.getApiSecret()).getBytes());
                VHttpClient.Response resp = http.post(
                    config.getOutboundUrl(), body,
                    "application/x-www-form-urlencoded",
                    Map.of("Authorization", "Basic " + auth));
                if (!resp.isSuccess())
                    log.warn("Twilio RCS send HTTP {}: {}", resp.statusCode(), resp.body());
            }
            case SINCH -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("from", config.getFromNumber());
                payload.put("to", java.util.List.of(msg.to()));
                payload.put("message", Map.of("type", "text", "text", msg.text()));
                VHttpClient.Response resp = http.post(
                    config.getOutboundUrl(),
                    mapper.writeValueAsString(payload),
                    "application/json",
                    Map.of("Authorization", "Bearer " + config.getApiKey()));
                if (!resp.isSuccess())
                    log.warn("Sinch RCS send HTTP {}: {}", resp.statusCode(), resp.body());
            }
            case MESSAGEBIRD -> {
                Map<String, Object> payload = Map.of(
                    "originator", config.getFromNumber(),
                    "recipients", java.util.List.of(msg.to()),
                    "body", msg.text(),
                    "type", "rcs"
                );
                VHttpClient.Response resp = http.post(
                    config.getOutboundUrl(),
                    mapper.writeValueAsString(payload),
                    "application/json",
                    Map.of("Authorization", "AccessKey " + config.getApiKey()));
                if (!resp.isSuccess())
                    log.warn("MessageBird RCS send HTTP {}: {}", resp.statusCode(), resp.body());
            }
            case GOOGLE_RBM -> {
                Map<String, Object> payload = Map.of(
                    "agentMessage", Map.of(
                        "contentMessage", Map.of("text", msg.text())
                    )
                );
                VHttpClient.Response resp = http.post(
                    config.getOutboundUrl() + "/" + encode(msg.to()) + "/agentMessages",
                    mapper.writeValueAsString(payload),
                    "application/json",
                    Map.of("Authorization", "Bearer " + config.getApiKey()));
                if (!resp.isSuccess())
                    log.warn("Google RBM send HTTP {}: {}", resp.statusCode(), resp.body());
            }
            default -> {
                // CUSTOM: generic JSON POST
                if (config.getOutboundUrl() == null)
                    throw new IllegalStateException("RcsConfig.withOutboundUrl() must be set for CUSTOM provider");
                Map<String, Object> payload = Map.of(
                    "from", config.getFromNumber(),
                    "to",   msg.to(),
                    "text", msg.text()
                );
                Map<String, String> headers = config.getApiKey() != null
                    ? Map.of("Authorization", "Bearer " + config.getApiKey())
                    : Map.of();
                VHttpClient.Response resp = http.post(
                    config.getOutboundUrl(),
                    mapper.writeValueAsString(payload),
                    "application/json",
                    headers);
                if (!resp.isSuccess())
                    log.warn("Custom RCS send HTTP {}: {}", resp.statusCode(), resp.body());
            }
        }
    }

    private static String encode(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
    }
}
