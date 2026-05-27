package dev.vatn.api;

import java.util.function.Consumer;

/**
 * Async messaging protocol for inter-node communication.
 * Binary-safe and designed for both local (in-JVM) and remote (Federated) talk.
 */
@VatnApi(since = "1.0")
public interface VMessaging extends VService {
    
    /**
     * Publishes a message to a specific topic/channel.
     * @param channel The channel name (e.g., "vnode.registry.update").
     * @param payload The binary message body.
     */
    void publish(String channel, byte[] payload);
    
    /**
     * Subscribes to a channel to receive messages.
     * @param channel The channel name.
     * @param callback Function to handle incoming binary payloads.
     */
    void subscribe(String channel, Consumer<byte[]> callback);

    /**
     * Removes a previously registered callback from a channel.
     * No-op if the callback was not registered.
     */
    @VatnApi(since = "1.1")
    default void unsubscribe(String channel, Consumer<byte[]> callback) {}

    /**
     * Returns the active transport type for this messaging service.
     * Defaults to LOCAL if not overridden.
     */
    @VatnApi(since = "1.0")
    default VTransport getTransport() {
        return VTransport.LOCAL;
    }

    /**
     * Sends a direct message to a specific node.
     * @param targetNodeId The destination node identifier.
     * @param payload The message body.
     */
    void sendDirect(String targetNodeId, byte[] payload);
}
