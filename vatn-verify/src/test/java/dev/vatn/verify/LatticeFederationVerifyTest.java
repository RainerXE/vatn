package dev.vatn.verify;

import dev.vatn.api.VDiscovery;
import dev.vatn.api.VNameResolver;
import dev.vatn.api.VNodePlugin;
import dev.vatn.core.VNodeRunner;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class LatticeFederationVerifyTest {

    @Test
    public void testLatticeServiceResolution() throws Exception {
        // Shared messaging for the test lattice
        dev.vatn.api.VMessaging sharedMessaging = new dev.vatn.core.transport.InProcessMessaging();

        // Node B hosts a dummy "weather-service"
        java.nio.file.Path idB = java.nio.file.Paths.get("target", "test-ids", "nodeB.pem");
        VNodeRunner nodeB = VNodeRunner.create(0, java.nio.file.Paths.get("plugins"), idB);
        nodeB.setMessagingOverride(sharedMessaging);
        nodeB.addPlugin(new VNodePlugin() {
            @Override public String getId() { return "weather-service"; }
            @Override public String getName() { return "Weather Service"; }
            @Override public String getVersion() { return "1.0.0"; }
        });
        nodeB.start();
        
        // Node A wants to use the weather-service
        java.nio.file.Path idA = java.nio.file.Paths.get("target", "test-ids", "nodeA.pem");
        VNodeRunner nodeA = VNodeRunner.create(0, java.nio.file.Paths.get("plugins"), idA);
        nodeA.setMessagingOverride(sharedMessaging);
        nodeA.start();

        try {
            VDiscovery discoveryA = nodeA.getContext().getService(VDiscovery.class).get();
            VNameResolver resolverA = nodeA.getContext().getService(VNameResolver.class).get();

            // Proactively request sync to catch Node B's state
            discoveryA.requestSync();

            // Wait for P2P discovery to sync (Lattice heartbeats)
            // We'll give it a few seconds since it happens over VMessaging (InProcess for test)
            Thread.sleep(3000);

            // Node A should be able to resolve vatn://weather-service
            URI resolvedUri = resolverA.resolve("vatn://weather-service/current").get(5, TimeUnit.SECONDS);
            
            assertNotNull(resolvedUri);
            assertTrue(resolvedUri.toString().contains("localhost:" + nodeB.getBoundPort()));
            assertTrue(resolvedUri.toString().endsWith("/current"));
            
            System.out.println("Lattice success! Resolved vatn://weather-service to: " + resolvedUri);

        } finally {
            nodeA.stop();
            nodeB.stop();
        }
    }
}
