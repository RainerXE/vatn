package dev.vatn.plugins.comm;

import dev.vatn.api.VService;

import java.util.Set;

/**
 * Unified communication service — send messages and register inbound handlers
 * across all active channels (Telegram, Signal, RCS) through a single API.
 *
 * <pre>{@code
 * CommService comm = ctx.getService(CommService.class).orElseThrow();
 *
 * // Receive from any channel
 * comm.onMessage(msg -> {
 *     log.info("[{}] {} -> {}", msg.channel(), msg.from(), msg.text());
 *     comm.send(OutboundMessage.replyTo(msg, "Echo: " + msg.text()));
 * });
 *
 * // Channel-scoped handler
 * comm.onMessage(CommChannel.TELEGRAM, msg ->
 *     handleTelegramCommand(msg));
 *
 * // Explicit send
 * comm.send(OutboundMessage.text(CommChannel.TELEGRAM, chatId, "Hello!"));
 * comm.send(OutboundMessage.text(CommChannel.SIGNAL,   "+49123…", "Alert: disk full"));
 * comm.send(OutboundMessage.text(CommChannel.RCS,      "+49456…", "Your code: 4821"));
 * }</pre>
 */
public interface CommService extends VService {

    /**
     * Send a message on the specified channel.
     *
     * @throws IllegalArgumentException if the requested channel is not active
     * @throws RuntimeException         if the underlying provider call fails
     */
    void send(OutboundMessage message);

    /**
     * Register a handler that receives inbound messages from ALL active channels.
     * Multiple handlers are supported; all are called in registration order.
     */
    void onMessage(MessageHandler handler);

    /**
     * Register a handler that receives inbound messages from ONE specific channel.
     */
    void onMessage(CommChannel channel, MessageHandler handler);

    /** Returns the set of channels that have at least one active agent. */
    Set<CommChannel> activeChannels();
}
