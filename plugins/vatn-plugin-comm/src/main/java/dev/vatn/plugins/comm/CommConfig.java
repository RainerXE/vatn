package dev.vatn.plugins.comm;

import dev.vatn.plugins.comm.rcs.RcsConfig;
import dev.vatn.plugins.comm.signal.SignalConfig;
import dev.vatn.plugins.comm.telegram.TelegramConfig;

/**
 * Top-level configuration for {@link CommPlugin}.
 *
 * <p>Enable one or more channels by calling the corresponding {@code with*} method.
 * Any channel not configured is simply not started — the plugin is a no-op for that channel.
 *
 * <pre>{@code
 * // Single channel
 * CommConfig.create()
 *     .withTelegram(TelegramConfig.polling("BOT_TOKEN"))
 *
 * // All three channels on one node (hub mode)
 * CommConfig.create()
 *     .withTelegram(TelegramConfig.polling("BOT_TOKEN"))
 *     .withSignal(SignalConfig.of("http://localhost:8080", "+49123456789"))
 *     .withRcs(RcsConfig.twilio("+49123456789", "ACCOUNT_SID", "AUTH_TOKEN"))
 *
 * // Production: active-passive on each channel
 * CommConfig.create()
 *     .withTelegram(TelegramConfig.polling("BOT_TOKEN")
 *         .withAgentMode(VAgentMode.activePassive().withFailoverTimeout(10_000)))
 *     .withSignal(SignalConfig.of("http://signal:8080", "+49123456789")
 *         .withAgentMode(VAgentMode.activePassive()))
 * }</pre>
 */
public final class CommConfig {

    private TelegramConfig telegramConfig;
    private SignalConfig   signalConfig;
    private RcsConfig      rcsConfig;

    private CommConfig() {}

    public static CommConfig create() { return new CommConfig(); }

    public CommConfig withTelegram(TelegramConfig config) { this.telegramConfig = config; return this; }
    public CommConfig withSignal(SignalConfig config)     { this.signalConfig   = config; return this; }
    public CommConfig withRcs(RcsConfig config)           { this.rcsConfig      = config; return this; }

    public TelegramConfig getTelegramConfig() { return telegramConfig; }
    public SignalConfig   getSignalConfig()   { return signalConfig; }
    public RcsConfig      getRcsConfig()      { return rcsConfig; }

    public boolean hasTelegram() { return telegramConfig != null; }
    public boolean hasSignal()   { return signalConfig   != null; }
    public boolean hasRcs()      { return rcsConfig      != null; }
}
