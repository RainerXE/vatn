package dev.vatn.plugins.slack;

import dev.vatn.api.VService;

/**
 * Slack notification service registered in the VATN node context by {@link SlackPlugin}.
 *
 * <pre>{@code
 * SlackService slack = ctx.getService(SlackService.class).orElseThrow();
 *
 * // Plain text notification
 * slack.notify("Pipeline *daily-etl* completed successfully.");
 *
 * // Raw Block Kit payload
 * slack.notifyRaw("""
 *     {"blocks":[{"type":"section","text":{"type":"mrkdwn","text":"*Alert:* disk > 90%"}}]}
 *     """);
 * }</pre>
 */
public interface SlackService extends VService {

    /** Posts a plain-text (Markdown) message to the configured webhook. */
    void notify(String text) throws Exception;

    /** Posts a raw JSON payload (e.g. Block Kit) to the configured webhook. */
    void notifyRaw(String jsonPayload) throws Exception;
}
