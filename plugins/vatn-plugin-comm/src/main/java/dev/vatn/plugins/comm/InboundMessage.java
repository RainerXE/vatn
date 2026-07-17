package dev.vatn.plugins.comm;

import java.time.Instant;

/**
 * A normalized inbound message received from any communication channel.
 *
 * @param channel   which channel the message arrived on
 * @param from      sender identifier (phone number, Telegram chat id, etc.)
 * @param text      plain-text body (may be empty for media-only messages)
 * @param mediaUrl  optional URL to an attached image/audio/document (null if none)
 * @param raw       raw payload from the provider for channel-specific handling
 * @param receivedAt when the message was received
 */
public record InboundMessage(
        CommChannel channel,
        String      from,
        String      text,
        String      mediaUrl,
        String      raw,
        Instant     receivedAt
) {
    public static InboundMessage of(CommChannel channel, String from, String text) {
        return new InboundMessage(channel, from, text, null, null, Instant.now());
    }

    public static InboundMessage of(CommChannel channel, String from, String text, String raw) {
        return new InboundMessage(channel, from, text, null, raw, Instant.now());
    }

    public boolean hasMedia() { return mediaUrl != null && !mediaUrl.isBlank(); }
}
