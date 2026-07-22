package dev.vatn.plugins.slack;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slack notifications plugin for VATN. Registers a {@link SlackService} backed
 * by an Incoming Webhook in the node context.
 *
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new SlackPlugin(SlackConfig.of("https://hooks.slack.com/services/…")))
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 *
 * // Inside a DAG operator (e.g. notify on pipeline completion)
 * SlackService slack = ctx.getService(SlackService.class).orElseThrow();
 * slack.notify("*daily-etl* finished — " + rowCount + " rows processed.");
 * }</pre>
 */
public class SlackPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(SlackPlugin.class);

    private final SlackConfig config;

    public SlackPlugin(SlackConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.slack"; }
    @Override public String getName()    { return "VATN Slack Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14-preview"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        SlackService service = new SlackWebhookService(config);
        ctx.registerService(SlackService.class, service);
        log.info("SlackService registered — webhook configured");
    }

    @Override
    public void onShutdown() {}
}
