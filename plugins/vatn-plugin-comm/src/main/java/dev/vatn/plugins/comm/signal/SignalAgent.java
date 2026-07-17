package dev.vatn.plugins.comm.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VAgent;
import dev.vatn.api.VAgentContext;
import dev.vatn.api.VAgentRole;
import dev.vatn.api.VHttpClient;
import dev.vatn.plugins.comm.CommChannel;
import dev.vatn.plugins.comm.CommBus;
import dev.vatn.plugins.comm.InboundMessage;
import dev.vatn.plugins.comm.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Signal channel agent backed by
 * <a href="https://github.com/bbernhard/signal-cli-rest-api">signal-cli-rest-api</a>.
 *
 * <p>Polls {@code GET /v1/receive/{number}} on the configured interval.
 * Only the PRIMARY agent polls — standby is idle and promotes instantly on failover.
 *
 * <p>Setup: run {@code signal-cli-rest-api} as a Docker sidecar linked to the
 * phone number, then point {@link SignalConfig} at its base URL.
 */
public class SignalAgent implements VAgent {

    private static final Logger log = LoggerFactory.getLogger(SignalAgent.class);

    private final SignalConfig config;
    private final CommBus service;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean running = false;
    private VAgentContext ctx;

    public SignalAgent(SignalConfig config, CommBus service) {
        this.config  = config;
        this.service = service;
    }

    @Override public String getId()          { return "comm.signal"; }
    @Override public String getChannelType() { return "signal"; }

    @Override
    public void onStart(VAgentContext ctx) {
        this.ctx     = ctx;
        this.running = true;

        service.registerSender(CommChannel.SIGNAL, this::sendMessage);

        verifyApiReachable(ctx);

        ctx.onRoleChange(role -> {
            if (role == VAgentRole.PRIMARY) startPollingLoop(ctx);
        });

        if (ctx.isPrimary()) startPollingLoop(ctx);
        else log.info("Signal agent standing by on node {}", ctx.getNodeId());
    }

    @Override
    public void onStop() {
        running = false;
    }

    @Override
    public void onPromoted(VAgentContext ctx) {
        log.info("Signal agent promoted to PRIMARY on node {}", ctx.getNodeId());
    }

    // ── polling ──────────────────────────────────────────────────────────────

    private void startPollingLoop(VAgentContext ctx) {
        Thread.ofVirtual().name("signal-poll").start(() -> {
            VHttpClient http = ctx.nodeContext().getService(VHttpClient.class).orElseThrow();
            String encodedNumber = URLEncoder.encode(config.getPhoneNumber(), StandardCharsets.UTF_8);
            String receiveUrl = config.getApiUrl() + "/v1/receive/" + encodedNumber;
            log.info("Signal polling started (interval={}ms)", config.getPollIntervalMs());
            while (running && ctx.isPrimary()) {
                try {
                    VHttpClient.Response resp = http.get(receiveUrl);
                    if (resp.isSuccess()) {
                        JsonNode messages = mapper.readTree(resp.body());
                        if (messages.isArray() && !messages.isEmpty()) {
                            for (JsonNode envelope : messages) {
                                handleEnvelope(envelope);
                            }
                            ctx.reportHealthy();
                        }
                    } else if (resp.statusCode() != 204) {
                        log.warn("Signal receive HTTP {}", resp.statusCode());
                    }
                    Thread.sleep(config.getPollIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running) {
                        log.warn("Signal poll error: {}", e.getMessage());
                        try { Thread.sleep(5_000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
            log.info("Signal polling stopped");
        });
    }

    private void handleEnvelope(JsonNode envelope) {
        JsonNode data = envelope.path("envelope");
        if (data.isMissingNode()) data = envelope;

        String from = data.path("source").asText(data.path("sourceNumber").asText("unknown"));
        JsonNode dm = data.path("dataMessage");
        if (dm.isMissingNode()) return;

        String text     = dm.path("message").asText("");
        String mediaUrl = null;
        if (dm.has("attachments")) {
            JsonNode attachments = dm.path("attachments");
            if (attachments.isArray() && !attachments.isEmpty()) {
                mediaUrl = config.getApiUrl() + "/v1/attachments/"
                    + attachments.get(0).path("id").asText();
            }
        }
        if (text.isBlank() && mediaUrl == null) return;

        service.dispatch(new InboundMessage(
            CommChannel.SIGNAL, from, text, mediaUrl,
            envelope.toString(), java.time.Instant.now()));
    }

    // ── send ──────────────────────────────────────────────────────────────────

    private void sendMessage(OutboundMessage msg) {
        VHttpClient http = ctx.nodeContext().getService(VHttpClient.class).orElseThrow();
        try {
            List<String> recipients = new ArrayList<>();
            recipients.add(msg.to());
            Map<String, Object> payload = Map.of(
                "message",    msg.text(),
                "number",     config.getPhoneNumber(),
                "recipients", recipients
            );
            String body = mapper.writeValueAsString(payload);
            VHttpClient.Response resp = http.post(
                config.getApiUrl() + "/v2/send", body, "application/json");
            if (!resp.isSuccess()) {
                log.warn("Signal send failed: HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Signal send error to {}", msg.to(), e);
            throw new RuntimeException("Signal send failed", e);
        }
    }

    private void verifyApiReachable(VAgentContext ctx) {
        try {
            VHttpClient http = ctx.nodeContext().getService(VHttpClient.class).orElseThrow();
            VHttpClient.Response resp = http.get(config.getApiUrl() + "/v1/about");
            log.info("signal-cli-rest-api reachable: {}", resp.body().replaceAll("\\s+", " ").substring(
                0, Math.min(80, resp.body().length())));
        } catch (Exception e) {
            log.warn("signal-cli-rest-api not reachable at {} — {}", config.getApiUrl(), e.getMessage());
        }
    }
}
