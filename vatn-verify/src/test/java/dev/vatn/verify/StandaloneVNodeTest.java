package dev.vatn.verify;

import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Standalone integration test for VATN.
 * Verifies that a node can boot and a plugin can perform message exchange.
 */
public class StandaloneVNodeTest {

    @Test
    public void testMessagingLoop() throws Exception {
        VNodeRunner runner = VNodeRunner.create(0);
        HelloVNode plugin = new HelloVNode();
        
        runner.addPlugin(plugin);
        runner.start();
        
        CompletableFuture<String> pongFuture = new CompletableFuture<>();
        
        // Subscribe to the PONG channel
        runner.getContext().getMessaging().subscribe("verify.pong", payload -> {
            pongFuture.complete(new String(payload));
        });
        
        // Send a PING
        runner.getContext().getMessaging().publish("verify.ping", "PING".getBytes());
        
        String result = pongFuture.get(2, TimeUnit.SECONDS);
        assertEquals("PONG", result, "Stand-alone messaging loop failed");
        
        runner.stop();
    }
}
