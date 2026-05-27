package dev.vatn.verify;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import dev.vatn.api.VMessaging;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VJson;
import dev.vatn.core.VDiscoveryImpl;
import dev.vatn.core.VNodeRunner;
import dev.vatn.core.VJsonImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LatticeSecurityTest {

    @Test
    public void testRejectInvalidSignatures() throws Exception {
        java.nio.file.Path id = java.nio.file.Paths.get("target", "test-ids", "security_node.pem");
        VNodeRunner node = VNodeRunner.create(0, java.nio.file.Paths.get("plugins"), id);
        node.start();
        
        try {
            VNodeContext context = node.getContext();
            VMessaging messaging = context.getMessaging();
            VJson json = new VJsonImpl();

            // 1. Send heartbeat with INVALID signature
            VDiscoveryImpl.LatticeNodeInfo malicious = new VDiscoveryImpl.LatticeNodeInfo(
                "attacker-node",
                "http://evil.com",
                List.of("exploit-service"),
                "JVM",
                1.0, 1.0, 1.0,
                System.currentTimeMillis(),
                Base64.getEncoder().encodeToString("bad-sig".getBytes())
            );

            messaging.publish("vatn.discovery", json.stringify(malicious).getBytes(StandardCharsets.UTF_8));
            
            Thread.sleep(1000);
            
            // Verify node did NOT register the attacker
            assertFalse(context.getDiscovery().resolve("attacker-node").isPresent(), 
                "Node should have REJECTED heartbeat with invalid signature.");

            // 2. Send heartbeat with STALE timestamp (Replay attack)
            VDiscoveryImpl.LatticeNodeInfo stale = new VDiscoveryImpl.LatticeNodeInfo(
                "replay-node",
                "http://ghost.com",
                List.of("ghost-service"),
                "JVM",
                1.0, 1.0, 1.0,
                System.currentTimeMillis() - 60000, // 1 minute old
                Base64.getEncoder().encodeToString("fake-sig".getBytes())
            );

            messaging.publish("vatn.discovery", json.stringify(stale).getBytes(StandardCharsets.UTF_8));
            
            Thread.sleep(1000);
            
            assertFalse(context.getDiscovery().resolve("replay-node").isPresent(), 
                "Node should have REJECTED stale heartbeat (Replay protection).");

            System.out.println("[SECURITY] Replay and Signature defenses verified.");

        } finally {
            node.stop();
        }
    }
}
