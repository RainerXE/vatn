package dev.vatn.core.transport;

import dev.vatn.api.VMessaging;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A lightweight, in-process implementation of VMessaging.
 * Uses a simple topic-based Pub/Sub model for inter-plugin communication within the same JVM.
 */
public class InProcessMessaging implements VMessaging {

    private static final Logger logger = LoggerFactory.getLogger(InProcessMessaging.class);
    private final Map<String, List<Consumer<byte[]>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(String channel, byte[] payload) {
        List<Consumer<byte[]>> consumers = subscribers.get(channel);
        if (consumers != null) {
            // Dispatch to all subscribers in this JVM using Virtual Threads (Project Loom)
            consumers.forEach(consumer -> {
                Thread.ofVirtual().start(() -> {
                    try {
                        consumer.accept(payload);
                    } catch (Exception e) {
                        logger.error("[VMessaging] Error in subscriber for channel {}", channel, e);
                    }
                });
            });
        }
    }

    @Override
    public void subscribe(String channel, Consumer<byte[]> callback) {
        subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    @Override
    public void sendDirect(String targetNodeId, byte[] payload) {
        // In-process implementation: if targetNodeId matches current node (global channel prefix),
        // we could route it. For now, we treat direct sends as a special "direct" channel.
        publish("direct." + targetNodeId, payload);
    }
}
