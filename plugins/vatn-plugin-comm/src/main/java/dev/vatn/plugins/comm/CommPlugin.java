package dev.vatn.plugins.comm;

import dev.vatn.api.VAgentMode;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.plugins.comm.rcs.RcsAgent;
import dev.vatn.plugins.comm.signal.SignalAgent;
import dev.vatn.plugins.comm.telegram.TelegramAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Communication sidecar plugin for VATN.
 *
 * <p>Registers one {@link VAgent} per configured channel (Telegram, Signal, RCS)
 * and exposes a single {@link CommService} that routes inbound dispatching and
 * outbound sending uniformly across all active channels.
 *
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new CommPlugin(CommConfig.create()
 *         .withTelegram(TelegramConfig.polling(System.getenv("TELEGRAM_TOKEN")))
 *         .withSignal(SignalConfig.of("http://signal-api:8080", "+49123456789"))
 *         .withRcs(RcsConfig.twilio("+49123456789",
 *             System.getenv("TWILIO_SID"), System.getenv("TWILIO_TOKEN")))))
 *     .addPlugin(new MyBotPlugin())
 *     .start();
 *
 * // Inside MyBotPlugin.onInitialize(ctx):
 * CommService comm = ctx.getService(CommService.class).orElseThrow();
 * comm.onMessage(msg -> comm.send(OutboundMessage.replyTo(msg, "Echo: " + msg.text())));
 * }</pre>
 */
public class CommPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(CommPlugin.class);

    private final CommConfig config;

    public CommPlugin(CommConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.comm"; }
    @Override public String getName()    { return "VATN Communication Sidecar Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.15-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        CommServiceImpl service = new CommServiceImpl();
        ctx.registerService(CommService.class, service);

        if (config.hasTelegram()) {
            TelegramAgent agent = new TelegramAgent(config.getTelegramConfig(), service);
            VAgentMode mode     = config.getTelegramConfig().getAgentMode();
            ctx.registerAgent(agent, mode);
            ctx.registerHealthCheck("comm.telegram", () -> true);
            log.info("Telegram channel configured (mode={}, strategy={})",
                config.getTelegramConfig().getMode(), mode.strategy());
        }

        if (config.hasSignal()) {
            SignalAgent agent = new SignalAgent(config.getSignalConfig(), service);
            VAgentMode mode   = config.getSignalConfig().getAgentMode();
            ctx.registerAgent(agent, mode);
            ctx.registerHealthCheck("comm.signal", () -> true);
            log.info("Signal channel configured (number={}, strategy={})",
                config.getSignalConfig().getPhoneNumber(), mode.strategy());
        }

        if (config.hasRcs()) {
            RcsAgent agent  = new RcsAgent(config.getRcsConfig(), service);
            VAgentMode mode = config.getRcsConfig().getAgentMode();
            ctx.registerAgent(agent, mode);
            ctx.registerHealthCheck("comm.rcs", () -> true);
            log.info("RCS channel configured (provider={}, strategy={})",
                config.getRcsConfig().getProvider(), mode.strategy());
        }

        int channelCount = (config.hasTelegram() ? 1 : 0)
                         + (config.hasSignal()   ? 1 : 0)
                         + (config.hasRcs()      ? 1 : 0);
        log.info("CommPlugin initialized — {} channel(s) registered", channelCount);
    }

    @Override
    public void onShutdown() {}
}
