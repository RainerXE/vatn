# vatn-plugin-activitypub
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Turns a VATN node into an ActivityPub actor so it can federate with Mastodon, Pixelfed, and other Fediverse software.

## How it works

Registers four Helidon HTTP routes (`/.well-known/webfinger`, `/ap/actor`, `/ap/inbox`, `/ap/outbox`) that implement the minimal ActivityPub server profile. Outbound `POST` requests to remote inboxes are signed with RSA-SHA256 HTTP Signatures using the configured private key. The inbox handler parses incoming `Follow` and `Undo` activities; other activity types are accepted but not acted upon. Key management is handled entirely inside `ActivityPubConfig` — use `ActivityPubConfig.generateKeyPair()` to produce a fresh RSA-2048 key pair.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-activitypub</artifactId>
    <version>1.0-alpha.15</version>
</dependency>
```

```java
ActivityPubConfig.KeyPairPem keys = ActivityPubConfig.generateKeyPair();

ActivityPubConfig config = ActivityPubConfig.of(
    "https://example.com",   // baseUrl
    "alice",                 // username
    "Alice",                 // displayName
    "Hello from VATN",       // summary
    keys.publicKeyPem(),
    keys.privateKeyPem()
);

VNodeRunner.create()
    .addPlugin(new ActivityPubPlugin(config))
    .run();
```

## API

```java
public interface ActivityPubService {
    String getActorUrl();
    String getPublicKeyPem();
    void sendActivity(String targetInbox, String activityJson);
}
```

```java
ActivityPubService ap = ctx.service(ActivityPubService.class);

// Actor URL for this node
String actorUrl = ap.getActorUrl();
// → "https://example.com/ap/actor"

// Send a Create/Note activity to a remote inbox
String activity = """
    {"@context":"https://www.w3.org/ns/activitystreams",
     "type":"Create","actor":"%s",
     "object":{"type":"Note","content":"Hello Fediverse!"}}
    """.formatted(actorUrl);
ap.sendActivity("https://mastodon.social/users/bob/inbox", activity);
```

## Configuration

| Option           | Default | Meaning                                      |
|------------------|---------|----------------------------------------------|
| `baseUrl`        | —       | Public HTTPS root of this node               |
| `username`       | —       | Local-part of the actor handle (`@user@host`) |
| `displayName`    | —       | Human-readable actor name                   |
| `summary`        | —       | Actor bio / profile description              |
| `publicKeyPem`   | —       | PEM-encoded RSA-2048 public key              |
| `privateKeyPem`  | —       | PEM-encoded RSA-2048 private key             |

## Notes

- `generateKeyPair()` is a convenience helper; store the resulting PEM strings externally (env vars, secrets manager) and reuse them across restarts.
- The inbox handler silently accepts unknown activity types — add your own route to process `Announce`, `Like`, etc.
- Outbound signatures follow the `(request-target) host date digest` header set required by Mastodon.
- The `/ap/outbox` endpoint returns an empty `OrderedCollection`; populating it requires custom route logic.
