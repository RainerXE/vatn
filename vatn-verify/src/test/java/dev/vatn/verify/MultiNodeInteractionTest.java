package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Multi-node integration test for VATN.
 * Verifies that independent nodes can communicate via the platform messaging layer.
 */
public class MultiNodeInteractionTest {

    @Test
    public void testCrossNodeMessaging() throws Exception {
        // In this implementation, the messaging is in-process but topic-scoped.
        // We simulate two "Logical Nodes" within the same JVM instance.
        
        VNodeRunner nodeA = VNodeRunner.create(0);
        VNodeRunner nodeB = VNodeRunner.create(0);
        
        // Shared messaging bus for multi-node in-process test
        dev.vatn.api.VMessaging sharedBus = new dev.vatn.core.transport.InProcessMessaging();
        nodeA.setMessagingOverride(sharedBus);
        nodeB.setMessagingOverride(sharedBus);
        
        HelloVNode pluginB = new HelloVNode();
        nodeB.addPlugin(pluginB); // Plugin B is on Node B
        
        nodeA.start();
        nodeB.start();
        
        CompletableFuture<String> pongFuture = new CompletableFuture<>();
        
        // Subscriber on Node A expects a response from Node B
        nodeA.getContext().getMessaging().subscribe("verify.pong", payload -> {
            pongFuture.complete(new String(payload));
        });
        
        // Node A sends a PING
        nodeA.getContext().getMessaging().publish("verify.ping", "PING".getBytes());
        
        String result = pongFuture.get(2, TimeUnit.SECONDS);
        assertEquals("PONG", result, "Cross-node messaging failed");
        
        nodeA.stop();
        nodeB.stop();
    }
}
