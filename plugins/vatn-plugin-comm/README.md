# vatn-plugin-comm — Communication Sidecar Plugin

A unified messaging hub for VATN that manages outbound agent connections to **Telegram**, **Signal**, and **RCS** channels. Each channel runs as a `VAgent` — a lifecycle-managed background component that owns its external connection. Active-passive failover is built in.

## Concept

Each channel agent follows the same pattern:

1. **Starts after all plugins are ready** — so it can use plugin services (database, Redis, etc.)
2. **Owns the connection** — only one node is PRIMARY per channel at a time
3. **Standby pre-connects** — when failover happens, promotion is near-instant
4. **Inbound and outbound through one API** — your plugin code only talks to `CommService`

```
Node A (PRIMARY)            Node B (STANDBY)
┌──────────────────┐        ┌──────────────────┐
│  TelegramAgent   │◄──┐    │  TelegramAgent   │
│  (polling)       │   │    │  (idle, ready)   │
│  SignalAgent     │   │    │  SignalAgent      │
│  RcsAgent        │   │    │  RcsAgent        │
│  (webhook recv)  │   │    │  (webhook recv)  │ ← both registered,
└──────────────────┘   │    └──────────────────┘   only PRIMARY processes
                       │
              heartbeat on VMessaging
              vatn.agent.comm.telegram.hb
              vatn.agent.comm.signal.hb
              vatn.agent.comm.rcs.hb
```

---

## Quick Start

### Maven dependency

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-comm</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Single channel (Telegram bot, development)

```java
VNodeRunner.create(8080)
    .addPlugin(new CommPlugin(CommConfig.create()
        .withTelegram(TelegramConfig.polling(System.getenv("TELEGRAM_TOKEN")))))
    .addPlugin(new MyBotPlugin())
    .start();
```

### All three channels (hub mode)

```java
CommConfig config = CommConfig.create()
    .withTelegram(TelegramConfig.polling(System.getenv("TELEGRAM_TOKEN")))
    .withSignal(SignalConfig.of("http://signal-api:8080", System.getenv("SIGNAL_NUMBER")))
    .withRcs(RcsConfig.twilio(
        System.getenv("TWILIO_FROM"),
        System.getenv("TWILIO_SID"),
        System.getenv("TWILIO_TOKEN")));

VNodeRunner.create(8080)
    .addPlugin(new CommPlugin(config))
    .addPlugin(new MyCrmPlugin())
    .start();
```

### Production: active-passive failover

```java
CommConfig config = CommConfig.create()
    .withTelegram(TelegramConfig.polling(System.getenv("TELEGRAM_TOKEN"))
        .withAgentMode(VAgentMode.activePassive()
            .withHeartbeatInterval(3_000)
            .withFailoverTimeout(10_000)))
    .withSignal(SignalConfig.of("http://signal-api:8080", System.getenv("SIGNAL_NUMBER"))
        .withAgentMode(VAgentMode.activePassive()));
```

Deploy two nodes with identical config. Node A becomes PRIMARY on startup; Node B monitors the heartbeat. If Node A fails, Node B promotes within 10 seconds.

---

## Receiving messages

Register handlers in any `VNodePlugin.onInitialize()` that runs after `CommPlugin`:

```java
public void onInitialize(VNodeContext ctx) {
    CommService comm = ctx.getService(CommService.class).orElseThrow();

    // All channels
    comm.onMessage(msg -> {
        log.info("[{}] from {} — {}", msg.channel(), msg.from(), msg.text());
    });

    // Channel-specific
    comm.onMessage(CommChannel.TELEGRAM, msg -> handleTelegramCommand(msg, comm));
    comm.onMessage(CommChannel.SIGNAL,   msg -> handleSignalAlert(msg, comm));
    comm.onMessage(CommChannel.RCS,      msg -> handleRcsInteraction(msg, comm));
}
```

### `InboundMessage` fields

| Field        | Type           | Notes                                         |
|--------------|----------------|-----------------------------------------------|
| `channel()`  | `CommChannel`  | `TELEGRAM`, `SIGNAL`, or `RCS`                |
| `from()`     | `String`       | Chat ID (Telegram), phone number (Signal/RCS) |
| `text()`     | `String`       | Plain-text body, empty for media-only         |
| `mediaUrl()` | `String`       | URL to attached file, null if none            |
| `raw()`      | `String`       | Raw JSON from the provider                    |
| `receivedAt()`| `Instant`     | Wall-clock time of receipt                    |

---

## Sending messages

```java
CommService comm = ctx.getService(CommService.class).orElseThrow();

// Explicit send
comm.send(OutboundMessage.text(CommChannel.TELEGRAM, chatId, "Hello!"));
comm.send(OutboundMessage.text(CommChannel.SIGNAL, "+49123456789", "Alert: disk full"));
comm.send(OutboundMessage.text(CommChannel.RCS,    "+49456789123", "Your OTP: 4821"));

// Reply to inbound message (same channel, same sender)
comm.onMessage(msg -> comm.send(OutboundMessage.replyTo(msg, "Got it: " + msg.text())));
```

---

## Channel configuration

### Telegram

```java
// Long polling — no public URL required
TelegramConfig.polling("7012345678:AAF_yourBotToken")

// Webhook — Telegram pushes to your URL
TelegramConfig.webhook("7012345678:AAF_yourBotToken", "/comm/telegram/webhook")
    .withWebhookSecret("your-secret-token")  // X-Telegram-Bot-Api-Secret-Token header

// Both modes support active-passive
TelegramConfig.polling("...")
    .withAgentMode(VAgentMode.activePassive().withFailoverTimeout(10_000))
```

**Polling notes**:
- Only the PRIMARY node polls — Telegram's getUpdates requires exactly one active consumer
- Standby agents wake up and start polling the moment they are promoted
- `offset` tracking is in-memory; after failover the standby re-reads recent updates (may see a brief replay window)

**Webhook notes**:
- Both nodes register the route, but only the PRIMARY processes incoming POSTs
- Standby returns HTTP 200 so Telegram doesn't retry
- Register the webhook URL with Telegram once: `https://api.telegram.org/bot{TOKEN}/setWebhook?url=https://your-node.example.com/comm/telegram/webhook`

---

### Signal

Signal requires a running [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api) sidecar. The phone number must be registered before the plugin starts.

```java
SignalConfig.of("http://localhost:8080", "+49123456789")
    .withPollIntervalMs(2_000)
    .withAgentMode(VAgentMode.activePassive())
```

**Docker sidecar setup**:

```yaml
# docker-compose.yml
services:
  signal-api:
    image: bbernhard/signal-cli-rest-api:latest
    environment:
      - MODE=json-rpc
    volumes:
      - ./signal-data:/home/.local/share/signal-cli
    ports:
      - "8080:8080"

  vatn-app:
    image: your-app
    environment:
      - SIGNAL_API=http://signal-api:8080
      - SIGNAL_NUMBER=+49123456789
    depends_on:
      - signal-api
```

**First-time linking** (run once before the plugin starts):

```bash
# Link existing Signal account via QR code
curl "http://localhost:8080/v1/qrcodelink?device_name=vatn-node" > qr.png
# Scan with Signal app: Settings → Linked devices → Link new device
```

---

### RCS

RCS is carrier-based. The plugin supports four providers:

#### Twilio

```java
RcsConfig.twilio("+49123456789", "ACCOUNT_SID", "AUTH_TOKEN")
```

Configure your Twilio number's webhook URL to POST to:
`https://your-node.example.com/comm/rcs/webhook`

#### Sinch

```java
RcsConfig.sinch("+49123456789",
    "https://us.rcs.api.sinch.com/rcs/v1/send",
    "SERVICE_ID",
    "API_KEY")
```

#### MessageBird

```java
RcsConfig.messageBird("+49123456789", "API_KEY")
```

#### Custom / Generic

```java
RcsConfig.custom("+49123456789")
    .withOutboundUrl("https://my-rcs-gateway/send")
    .withApiKey("bearer-token")
    .withWebhookPath("/comm/rcs/webhook")
    .withWebhookSecret("shared-secret")  // checked against X-Webhook-Signature header
```

**Inbound webhook payload** for CUSTOM providers should contain at least:
- A `from` / `sender` / `msisdn` field
- A `text` / `body` / `message` field

The full raw JSON is always available via `InboundMessage.raw()`.

---

## Failover and the twin pattern

### Active-passive (recommended for production)

```
Node A  ──heartbeat──►  vatn.agent.comm.telegram.hb
Node B  ◄──listening──  (watches for silence)

If Node A goes silent for failoverTimeoutMs:
  Node B promotes itself → starts polling → publishes .promoted
```

Both nodes must have identical `CommPlugin` configuration. The VMessaging transport (OIPC) carries the heartbeat across nodes.

### Twin (for stateless webhook receivers)

```java
TelegramConfig.webhook("BOT_TOKEN", "/comm/telegram/webhook")
    .withAgentMode(VAgentMode.twin())
```

Both nodes register the webhook route and receive traffic. Useful when a load balancer distributes webhook POSTs across nodes and you want all nodes to process them. Both agents call handlers — your application code is responsible for deduplication (e.g. using a Redis `SETNX` on `update_id`).

---

## Integration example: Frejay AI assistant

```java
public class FrejayCommPlugin implements VNodePlugin {

    @Override
    public void onInitialize(VNodeContext ctx) {
        CommService comm = ctx.getService(CommService.class).orElseThrow();
        AiService  ai   = ctx.getService(AiService.class).orElseThrow();

        comm.onMessage(msg -> {
            // Route to AI, reply on same channel
            String reply = ai.chat(msg.from(), msg.text());
            comm.send(OutboundMessage.replyTo(msg, reply));
        });

        // Proactive alert from a DAG
        ctx.getService(VDagEngine.class).ifPresent(engine -> {
            engine.onRunComplete(run -> {
                if (run.hasFailed()) {
                    comm.send(OutboundMessage.text(
                        CommChannel.TELEGRAM,
                        System.getenv("ADMIN_CHAT_ID"),
                        "DAG " + run.getId() + " failed: " + run.errorSummary()));
                }
            });
        });
    }
}
```

---

## Health checks

The plugin registers a health check per active channel. These appear in `GET /health`:

```json
{
  "status": "UP",
  "checks": {
    "comm.telegram": "UP",
    "comm.signal":   "UP",
    "comm.rcs":      "UP"
  }
}
```

The checks are currently connectivity-level (agent started). Extend them by overriding the health check registration in your own plugin:

```java
ctx.registerHealthCheck("comm.telegram", () ->
    comm.activeChannels().contains(CommChannel.TELEGRAM));
```

---

## Architecture notes

- **No external bot SDK dependency** — HTTP calls are made through VATN's `VHttpClient`, which supports future sandboxing, SSRF guards, and audit logging
- **Virtual threads throughout** — polling loops, message handlers, and send calls all run on virtual threads
- **Agent lifetime is independent of plugin lifetime** — agents start after `onReady()`, stop before plugin `onShutdown()`, so they can use plugin services during teardown
- **VMessaging carries the heartbeat** — on a single node, this is in-JVM; across OIPC-connected nodes it's network-carried automatically
