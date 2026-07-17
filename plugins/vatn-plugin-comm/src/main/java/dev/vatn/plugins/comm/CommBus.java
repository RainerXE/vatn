package dev.vatn.plugins.comm;

/**
 * Internal interface used by channel agents to dispatch inbound messages and
 * register their outbound send capability. Not intended for application code
 * — use {@link CommService} instead.
 */
public interface CommBus {

    /** Called by an agent when an inbound message arrives. */
    void dispatch(InboundMessage message);

    /** Called by an agent during {@code onStart} to register its send function. */
    void registerSender(CommChannel channel, ChannelSender sender);

    @FunctionalInterface
    interface ChannelSender {
        void send(OutboundMessage message);
    }
}
