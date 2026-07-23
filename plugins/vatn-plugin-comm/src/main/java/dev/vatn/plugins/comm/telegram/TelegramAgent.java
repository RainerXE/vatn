package dev.vatn.plugins.comm.telegram;

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

import java.time.Duration;
import java.util.Map;

/**
 * Telegram channel agent backed by the Bot API.
 *
 * <p>Supports two receive modes:
 * <ul>
 *   <li><b>POLLING</b> — long-polls {@code getUpdates} in a virtual thread. No public URL needed.
 *       Only the PRIMARY agent polls; standby agents are idle and promote instantly on failover.</li>
 *   <li><b>WEBHOOK</b> — registers a VATN HTTP route that receives Telegram push updates.
 *       The route processes messages only on the PRIMARY node (ignores requests on STANDBY).</li>
 * </ul>
 */
public class TelegramAgent implements VAgent {

    private static final Logger log = LoggerFactory.getLogger(TelegramAgent.class);
    private static final String BASE = "https://api.telegram.org/bot";

    private final TelegramConfig config;
    private final CommBus service;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean running = false;
    private VAgentContext ctx;

    public TelegramAgent(TelegramConfig config, CommBus service) {
        this.config  = config;
        this.service = service;
    }

    @Override public String getId()          { return "comm.telegram"; }
    @Override public String getChannelType() { return "telegram"; }

    @Override
    public void onStart(VAgentContext ctx) {
        this.ctx     = ctx;
        this.running = true;

        service.registerSender(CommChannel.TELEGRAM, this::sendMessage);

        ctx.onRoleChange(role -> {
            if (role == VAgentRole.PRIMARY && config.getMode() == TelegramConfig.Mode.POLLING) {
                startPollingLoop(ctx);
            }
        });

        switch (config.getMode()) {
            case POLLING -> {
                if (ctx.isPrimary()) startPollingLoop(ctx);
                else log.info("Telegram agent standing by — will poll on promotion");
            }
            case WEBHOOK -> registerWebhookRoute(ctx);
        }
    }

    @Override
    public void onStop() {
        running = false;
    }

    @Override
    public void onPromoted(VAgentContext ctx) {
        log.info("Telegram agent promoted to PRIMARY on node {}", ctx.getNodeId());
    }

    @Override
    public void onDemoted() {
        log.info("Telegram agent stepping down from PRIMARY");
    }

    // ── polling ──────────────────────────────────────────────────────────────

    private void startPollingLoop(VAgentContext ctx) {
        Thread.ofVirtual().name("telegram-poll").start(() -> {
            VHttpClient http = ctx.nodeContext().getService(VHttpClient.class).orElseThrow(
                () -> new IllegalStateException("VHttpClient not registered — add it before CommPlugin"));
            long offset = 0;
            log.info("Telegram long-poll started (timeout={}s)", config.getPollTimeoutSec());
            while (running && ctx.isPrimary()) {
                try {
                    String url = BASE + config.getToken() + "/getUpdates"
                        + "?offset=" + offset
                        + "&timeout=" + config.getPollTimeoutSec()
                        + "&allowed_updates=[\"message\",\"callback_query\"]";
                    VHttpClient.Response resp = http.get(url, Map.of(),
                        Duration.ofSeconds(config.getPollTimeoutSec() + 10L));
                    if (!resp.isSuccess()) {
                        log.warn("Telegram getUpdates HTTP {}", resp.statusCode());
                        Thread.sleep(5_000);
                        continue;
                    }
                    JsonNode root = mapper.readTree(resp.body());
                    if (root.path("ok").asBoolean()) {
                        for (JsonNode update : root.path("result")) {
                            offset = update.path("update_id").asLong() + 1;
                            handleUpdate(update);
                            ctx.reportHealthy();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running) {
                        log.warn("Telegram poll error: {}", e.getMessage());
                        try { Thread.sleep(3_000); } catch (InterruptedException ie) { break; }
                    }
                }
            }
            log.info("Telegram polling stopped");
        });
    }

    private void handleUpdate(JsonNode update) {
        JsonNode msg = update.path("message");
        if (msg.isMissingNode()) msg = update.path("callback_query").path("message");
        if (msg.isMissingNode()) return;

        String chatId   = msg.path("chat").path("id").asText();
        String text     = msg.path("text").asText("");
        String mediaUrl = extractMediaUrl(msg);

        if (text.isBlank() && mediaUrl == null) return;

        service.dispatch(new InboundMessage(
            CommChannel.TELEGRAM, chatId, text, mediaUrl,
            update.toString(), java.time.Instant.now()));
    }

    private String extractMediaUrl(JsonNode msg) {
        // Telegram gives file_id; a real implementation would resolve via getFile
        if (!msg.path("photo").isMissingNode()) {
            JsonNode photos = msg.path("photo");
            return "tg://file/" + photos.get(photos.size() - 1).path("file_id").asText();
        }
        if (!msg.path("document").isMissingNode())
            return "tg://file/" + msg.path("document").path("file_id").asText();
        return null;
    }

    // ── webhook ───────────────────────────────────────────────────────────────

    private void registerWebhookRoute(VAgentContext ctx) {
        String path = config.getWebhookPath();
        ctx.nodeContext().register(path, routes ->
            routes.post("", (req, res) -> {
                if (!ctx.isPrimary()) { res.status(200).send("standby"); return; }
                if (config.getWebhookSecret() != null) {
                    String secret = req.getHeader("X-Telegram-Bot-Api-Secret-Token");
                    if (!config.getWebhookSecret().equals(secret)) {
                        res.status(403).send("forbidden");
                        return;
                    }
                }
                try {
                    JsonNode update = mapper.readTree(req.getBody());
                    handleUpdate(update);
                } catch (Exception e) {
                    log.warn("Telegram webhook parse error: {}", e.getMessage());
                }
                res.status(200).send("ok");
            })
        );
        log.info("Telegram webhook route registered at {}", path);
    }

    // ── send ──────────────────────────────────────────────────────────────────

    private void sendMessage(OutboundMessage msg) {
        VHttpClient http = ctx.nodeContext().getService(VHttpClient.class).orElseThrow();
        try {
            String url = BASE + config.getToken() + "/sendMessage";
            String body = mapper.writeValueAsString(Map.of(
                "chat_id",    msg.to(),
                "text",       msg.text(),
                "parse_mode", "HTML"
            ));
            VHttpClient.Response resp = http.post(url, body, "application/json");
            if (!resp.isSuccess()) {
                log.warn("Telegram sendMessage failed: HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Telegram send error to {}", msg.to(), e);
            throw new RuntimeException("Telegram send failed", e);
        }
    }
}
