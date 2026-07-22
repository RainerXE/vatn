# vatn-plugin-email
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Sends email via SMTP from a VATN node.

## How it works

Uses Jakarta Mail (the Angus reference implementation) to deliver messages over SMTP. Each `send` call creates a new `Session` from the configured properties, which keeps the plugin stateless and avoids connection-state issues in long-lived nodes. STARTTLS and SSL/TLS are both supported via config flags. The `EmailService` interface exposes default convenience methods `sendText` and `sendHtml` built on top of the core `send(EmailMessage)` method.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-email</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
EmailConfig config = EmailConfig.of("smtp.example.com", 587, "user@example.com", "secret")
    .withStarttls(true)
    .withFrom("noreply@example.com");

VNodeRunner.create()
    .addPlugin(new EmailPlugin(config))
    .run();
```

## API

```java
public interface EmailService {
    void send(EmailMessage message);

    // Default convenience methods:
    default void sendText(String to, String subject, String body);
    default void sendHtml(String to, String subject, String html);
}
```

```java
EmailService email = ctx.service(EmailService.class);

// Simple text email
email.sendText("alice@example.com", "Welcome!", "Hello Alice.");

// HTML email with CC and BCC
EmailMessage msg = EmailMessage.html("bob@example.com", "Report", "<h1>Done</h1>")
    .withCc("manager@example.com")
    .withBcc("archive@example.com")
    .withReplyTo("support@example.com");
email.send(msg);
```

## Configuration

| Option      | Default | Meaning                                              |
|-------------|---------|------------------------------------------------------|
| `host`      | —       | SMTP server hostname                                 |
| `port`      | —       | SMTP server port (typically 25, 465, or 587)         |
| `username`  | —       | SMTP authentication username                         |
| `password`  | —       | SMTP authentication password                         |
| `ssl`       | `false` | Use SSL/TLS on connect (port 465 style)              |
| `starttls`  | `false` | Upgrade plain connection to TLS via STARTTLS         |
| `from`      | —       | `From` address used when not set on the message      |

## Notes

- A new SMTP `Session` is opened per `send` call; for high-volume sending consider a dedicated mail relay that handles connection pooling.
- `withSsl(true)` and `withStarttls(true)` are mutually exclusive — set only one.
- `EmailMessage.text` and `EmailMessage.html` are static factory methods; both return a builder that accepts `withCc`, `withBcc`, and `withReplyTo`.
- If no `from` address is set in config or on the message, the SMTP server's envelope sender is used, which may cause delivery failures.
