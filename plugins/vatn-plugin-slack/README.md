# vatn-plugin-slack

Sends Slack notifications via an Incoming Webhook URL.

## How it works

Stateless HTTP POST to a Slack Incoming Webhook endpoint using VATN's built-in `VHttpClient`. The JSON payload is `{"text":"..."}`. There is no Slack SDK, no persistent connection, and no OAuth flow — just a plain HTTPS call per notification. The `notify(message, channel)` overload passes a `channel` field in the payload, which is only honoured if the webhook was configured to allow channel routing.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-slack</artifactId>
    <version>1.0-alpha.12</version>
</dependency>
```

```java
SlackConfig config = SlackConfig.of(System.getenv("SLACK_WEBHOOK_URL"));

VNodeRunner.create()
    .addPlugin(new SlackPlugin(config))
    .run();
```

## API

```java
public interface SlackService {
    void notify(String message);
    void notify(String message, String channel);
}
```

```java
SlackService slack = ctx.service(SlackService.class);

// Send to the webhook's default channel
slack.notify("Deployment to production completed.");

// Request a specific channel (webhook must support routing)
slack.notify("Health check failed: postgres", "#alerts");
```

## Configuration

| Option       | Default | Meaning                                                  |
|--------------|---------|----------------------------------------------------------|
| `webhookUrl` | —       | Slack Incoming Webhook URL from the Slack app settings   |

## Notes

- The webhook URL includes the secret token; store it in an environment variable rather than source code.
- Channel routing via `notify(message, channel)` only works if the Slack app's webhook is configured with the "Send messages to any channel" permission; otherwise the `channel` field is silently ignored by Slack.
- Slack's Incoming Webhooks do not support message threading, attachments, or Block Kit via this plugin — use the full Slack Web API client if you need those features.
- Each call is synchronous and blocks until Slack's servers respond; wrap in a virtual thread if called from a latency-sensitive handler.
