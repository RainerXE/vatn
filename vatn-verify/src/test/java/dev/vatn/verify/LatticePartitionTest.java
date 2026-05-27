package dev.vatn.verify;

import dev.vatn.api.VMessaging;
import dev.vatn.core.VNodeRunner;
import dev.vatn.core.transport.InProcessMessaging;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class LatticePartitionTest {

    @Test
    public void testSplitBrainRecovery() throws Exception {
        PartitionBus sharedBus = new PartitionBus();
        
        // 1. Activate Partition FIRST: Groups must be isolated from startup
        sharedBus.setPartitionActive(true);
        System.out.println("[TEST] Partition ACTIVE: Group 1 (A,B) isolated from Group 2 (C,D)");

        // Group 1: Nodes A and B
        VNodeRunner nodeA = createNode("A", 1, sharedBus);
        VNodeRunner nodeB = createNode("B", 1, sharedBus);

        // Group 2: Nodes C and D
        VNodeRunner nodeC = createNode("C", 2, sharedBus);
        VNodeRunner nodeD = createNode("D", 2, sharedBus);

        try {
            // Force announcements
            nodeB.getContext().getDiscovery().requestSync();
            nodeC.getContext().getDiscovery().requestSync();
            
            Thread.sleep(3000);

            // A should see B (same group)
            assertTrue(nodeA.getContext().getDiscovery().resolve(nodeB.getContext().getNodeId()).isPresent(), 
                "A should see B (same group 1)");
            
            // A should NOT see C (different group)
            assertFalse(nodeA.getContext().getDiscovery().resolve(nodeC.getContext().getNodeId()).isPresent(), 
                "A should NOT see C (partitioned group 2)");

            // 2. Lift Partition: Lattice should heal
            System.out.println("[TEST] Lifting partition. Lattice should heal...");
            sharedBus.setPartitionActive(false);
            
            nodeA.getContext().getDiscovery().requestSync();
            Thread.sleep(3000);

            // A should now see C
            assertTrue(nodeA.getContext().getDiscovery().resolve(nodeC.getContext().getNodeId()).isPresent(), 
                "A should see C after healing partition");
            
            System.out.println("[TEST] Split-brain recovery verified.");

        } finally {
            nodeA.stop(); nodeB.stop(); nodeC.stop(); nodeD.stop();
        }
    }

    private VNodeRunner createNode(String label, int groupId, PartitionBus bus) {
        java.nio.file.Path tempId = java.nio.file.Paths.get("target", "test-ids", label + ".pem");
        VNodeRunner node = VNodeRunner.create(0, java.nio.file.Paths.get("plugins"), tempId);
        node.setMessagingOverride(new FilteredMessaging(node, groupId, bus));
        node.start();
        return node;
    }

    /**
     * A central bus that tracks node groups and partition state.
     */
    private static class PartitionBus {
        private final Map<String, Integer> nodeGroups = new ConcurrentHashMap<>();
        private boolean partitionActive = false;
        private final InProcessMessaging internal = new InProcessMessaging();

        public void registerNode(String nodeId, int groupId) {
            nodeGroups.put(nodeId, groupId);
        }

        public void setPartitionActive(boolean active) { this.partitionActive = active; }
    }

    private static class FilteredMessaging implements VMessaging {
        private final VNodeRunner runner;
        private final int groupId;
        private final PartitionBus bus;
        private String cachedNodeId;

        public FilteredMessaging(VNodeRunner runner, int groupId, PartitionBus bus) {
            this.runner = runner;
            this.groupId = groupId;
            this.bus = bus;
        }

        private String getNodeId() {
            if (cachedNodeId == null && runner.getContext() != null) {
                cachedNodeId = runner.getContext().getNodeId();
                bus.registerNode(cachedNodeId, groupId);
            }
            return cachedNodeId;
        }

        @Override
        public void publish(String channel, byte[] payload) {
            getNodeId(); // Ensure registered
            bus.internal.publish(channel, payload);
        }

        @Override
        public void subscribe(String channel, Consumer<byte[]> callback) {
            bus.internal.subscribe(channel, payload -> {
                // Heuristic: If we are partitioned, check if sender is in our group
                // For this test, heartbeats are JSON. We can extract the nodeId.
                if (bus.partitionActive && channel.startsWith("vatn.discovery")) {
                    try {
                        String msg = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                        dev.vatn.api.VJson json = new dev.vatn.core.VJsonImpl();
                        dev.vatn.core.VDiscoveryImpl.LatticeNodeInfo info = json.parse(msg, dev.vatn.core.VDiscoveryImpl.LatticeNodeInfo.class);
                        String senderId = info.nodeId();
                        Integer senderGroup = bus.nodeGroups.get(senderId);
                        if (senderGroup != null && !senderGroup.equals(this.groupId)) {
                            // Block message from different group
                            return;
                        }
                    } catch (Exception e) {
                        // Ignore malformed or unparseable messages in the proxy
                    }
                }
                callback.accept(payload);
            });
        }

        @Override public void sendDirect(String target, byte[] payload) { bus.internal.sendDirect(target, payload); }
    }
}
