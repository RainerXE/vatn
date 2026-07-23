package dev.vatn.plugins.slack;

/**
 * Configuration for the Slack notifications plugin.
 *
 * <pre>{@code
 * SlackConfig config = SlackConfig.of("https://hooks.slack.com/services/T…/B…/…");
 * }</pre>
 */
public final class SlackConfig {

    private final String webhookUrl;
    private final int timeoutSeconds;

    private SlackConfig(String webhookUrl, int timeoutSeconds) {
        this.webhookUrl = webhookUrl;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static SlackConfig of(String webhookUrl) {
        return new SlackConfig(webhookUrl, 10);
    }

    public SlackConfig withTimeoutSeconds(int timeoutSeconds) {
        return new SlackConfig(webhookUrl, timeoutSeconds);
    }

    public String getWebhookUrl()     { return webhookUrl; }
    public int getTimeoutSeconds()    { return timeoutSeconds; }
}
