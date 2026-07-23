package dev.vatn.plugins.email;

import java.util.List;

/** An outbound email message. */
public record EmailMessage(
        List<String> to,
        String subject,
        String body,
        boolean html
) {
    /** Plain-text email to a single recipient. */
    public static EmailMessage plain(String to, String subject, String body) {
        return new EmailMessage(List.of(to), subject, body, false);
    }

    /** HTML email to a single recipient. */
    public static EmailMessage html(String to, String subject, String html) {
        return new EmailMessage(List.of(to), subject, html, true);
    }

    /** Plain-text email to multiple recipients. */
    public static EmailMessage plain(List<String> to, String subject, String body) {
        return new EmailMessage(List.copyOf(to), subject, body, false);
    }
}
