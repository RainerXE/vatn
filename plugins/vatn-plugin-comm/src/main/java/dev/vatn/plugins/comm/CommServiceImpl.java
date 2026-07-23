package dev.vatn.plugins.comm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class CommServiceImpl implements CommService, CommBus {

    private static final Logger log = LoggerFactory.getLogger(CommServiceImpl.class);

    private final List<MessageHandler> globalHandlers = new CopyOnWriteArrayList<>();
    private final Map<CommChannel, List<MessageHandler>> channelHandlers = new EnumMap<>(CommChannel.class);
    private final Map<CommChannel, CommBus.ChannelSender> senders = new ConcurrentHashMap<>();

    CommServiceImpl() {
        for (CommChannel ch : CommChannel.values()) {
            channelHandlers.put(ch, new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public void registerSender(CommChannel channel, CommBus.ChannelSender sender) {
        senders.put(channel, sender);
        log.info("CommService: channel {} is now active", channel);
    }

    @Override
    public void dispatch(InboundMessage msg) {
        globalHandlers.forEach(h -> invokeHandler(h, msg));
        channelHandlers.get(msg.channel()).forEach(h -> invokeHandler(h, msg));
    }

    @Override
    public void send(OutboundMessage message) {
        ChannelSender sender = senders.get(message.channel());
        if (sender == null) {
            throw new IllegalArgumentException("Channel " + message.channel() + " is not active on this node");
        }
        sender.send(message);
    }

    @Override
    public void onMessage(MessageHandler handler) {
        globalHandlers.add(handler);
    }

    @Override
    public void onMessage(CommChannel channel, MessageHandler handler) {
        channelHandlers.get(channel).add(handler);
    }

    @Override
    public Set<CommChannel> activeChannels() {
        return Set.copyOf(senders.keySet());
    }

    private void invokeHandler(MessageHandler handler, InboundMessage msg) {
        Thread.ofVirtual().start(() -> {
            try { handler.handle(msg); }
            catch (Exception e) { log.warn("Message handler threw on [{}] from {}", msg.channel(), msg.from(), e); }
        });
    }
}
