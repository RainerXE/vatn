package dev.vatn.plugins.email;

import dev.vatn.api.VService;

/**
 * Email sender service registered in the VATN node context by {@link EmailPlugin}.
 *
 * <pre>{@code
 * EmailService email = ctx.getService(EmailService.class).orElseThrow();
 *
 * // One-liner
 * email.send("alice@example.com", "Report ready", "Your daily report is attached.");
 *
 * // Full message
 * email.send(EmailMessage.html("bob@example.com", "Welcome!", "<h1>Hello!</h1>"));
 * }</pre>
 */
public interface EmailService extends VService {

    /** Sends the given message. */
    void send(EmailMessage message) throws Exception;

    /** Convenience method — sends a plain-text email to a single recipient. */
    default void send(String to, String subject, String body) throws Exception {
        send(EmailMessage.plain(to, subject, body));
    }
}
