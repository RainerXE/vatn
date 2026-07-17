package dev.vatn.plugins.comm.telegram;

import dev.vatn.api.VAgentMode;

/**
 * Configuration for the Telegram channel agent.
 *
 * <pre>{@code
 * TelegramConfig.polling("7012345678:AAF...")
 *
 * TelegramConfig.polling("7012345678:AAF...")
 *     .withAgentMode(VAgentMode.activePassive().withFailoverTimeout(10_000))
 * }</pre>
 */
public final class TelegramConfig {

    public enum Mode { POLLING, WEBHOOK }

    private final String token;
    private final Mode mode;
    private final String webhookPath;
    private final String webhookSecret;
    private final int pollTimeoutSec;
    private final VAgentMode agentMode;

    private TelegramConfig(String token, Mode mode, String webhookPath,
                           String webhookSecret, int pollTimeoutSec, VAgentMode agentMode) {
        this.token          = token;
        this.mode           = mode;
        this.webhookPath    = webhookPath;
        this.webhookSecret  = webhookSecret;
        this.pollTimeoutSec = pollTimeoutSec;
        this.agentMode      = agentMode;
    }

    /** Long-polling mode — no public URL required. Suitable for development and simple deployments. */
    public static TelegramConfig polling(String botToken) {
        return new TelegramConfig(botToken, Mode.POLLING, null, null, 25, VAgentMode.singleton());
    }

    /** Webhook mode — Telegram pushes updates to {@code webhookPath}. Requires a public HTTPS URL. */
    public static TelegramConfig webhook(String botToken, String webhookPath) {
        return new TelegramConfig(botToken, Mode.WEBHOOK, webhookPath, null, 25, VAgentMode.singleton());
    }

    public TelegramConfig withWebhookSecret(String secret) {
        return new TelegramConfig(token, mode, webhookPath, secret, pollTimeoutSec, agentMode);
    }

    public TelegramConfig withPollTimeout(int seconds) {
        return new TelegramConfig(token, mode, webhookPath, webhookSecret, seconds, agentMode);
    }

    public TelegramConfig withAgentMode(VAgentMode mode) {
        return new TelegramConfig(token, this.mode, webhookPath, webhookSecret, pollTimeoutSec, mode);
    }

    public String  getToken()          { return token; }
    public Mode    getMode()           { return mode; }
    public String  getWebhookPath()    { return webhookPath != null ? webhookPath : "/comm/telegram/webhook"; }
    public String  getWebhookSecret()  { return webhookSecret; }
    public int     getPollTimeoutSec() { return pollTimeoutSec; }
    public VAgentMode getAgentMode()   { return agentMode; }
}
