package dev.vatn.plugins.comm;

/**
 * A message to be sent out on a specific communication channel.
 *
 * @param channel  which channel to send on
 * @param to       recipient identifier (phone number, Telegram chat id, etc.)
 * @param text     message body
 * @param mediaUrl optional URL to attach (null for text-only)
 */
public record OutboundMessage(
        CommChannel channel,
        String      to,
        String      text,
        String      mediaUrl
) {
    public static OutboundMessage text(CommChannel channel, String to, String text) {
        return new OutboundMessage(channel, to, text, null);
    }

    public static OutboundMessage media(CommChannel channel, String to, String caption, String mediaUrl) {
        return new OutboundMessage(channel, to, caption, mediaUrl);
    }

    /** Reply to an inbound message on the same channel and from the same sender. */
    public static OutboundMessage replyTo(InboundMessage msg, String text) {
        return new OutboundMessage(msg.channel(), msg.from(), text, null);
    }
}
