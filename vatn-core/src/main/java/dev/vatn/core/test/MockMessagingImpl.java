package dev.vatn.core.test;

import dev.vatn.api.VMessaging;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory Mock implementation of VMessaging for testing.
 * Records all published messages for assertions and allows manual triggering of subscribers.
 */
public class MockMessagingImpl implements VMessaging {
    private final Map<String, List<Consumer<byte[]>>> subscribers = new ConcurrentHashMap<>();
    private final List<PublishedMessage> publishedMessages = new ArrayList<>();

    public record PublishedMessage(String channel, byte[] payload) {}

    @Override
    public void publish(String channel, byte[] payload) {
        publishedMessages.add(new PublishedMessage(channel, payload));
        // Also trigger local subscribers immediately for in-process test logic
        List<Consumer<byte[]>> subs = subscribers.get(channel);
        if (subs != null) {
            for (Consumer<byte[]> sub : subs) {
                sub.accept(payload);
            }
        }
    }

    @Override
    public void subscribe(String channel, Consumer<byte[]> callback) {
        subscribers.computeIfAbsent(channel, k -> new ArrayList<>()).add(callback);
    }

    public List<PublishedMessage> getPublishedMessages() {
        return new ArrayList<>(publishedMessages);
    }

    @Override
    public void sendDirect(String targetNodeId, byte[] payload) {
        // For testing, we record direct messages as well, using a special channel prefix
        publishedMessages.add(new PublishedMessage("direct://" + targetNodeId, payload));
    }

    public void simulateIncoming(String channel, byte[] payload) {
        List<Consumer<byte[]>> subs = subscribers.get(channel);
        if (subs != null) {
            for (Consumer<byte[]> sub : subs) {
                sub.accept(payload);
            }
        }
    }

    public void clear() {
        publishedMessages.clear();
        subscribers.clear();
    }
}
