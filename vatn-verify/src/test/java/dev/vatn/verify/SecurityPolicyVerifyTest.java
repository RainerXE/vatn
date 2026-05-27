package dev.vatn.verify;

import dev.vatn.api.VatnSecurity;
import dev.vatn.api.security.VFlowPolicy;
import dev.vatn.api.security.VPolicyInterjector;
import dev.vatn.api.security.VTrustLevel;
import dev.vatn.core.VNodeRunner;
import dev.vatn.core.VStreamServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification of the VATN Phase 2.5 Security Policies.
 * Ensures that Trust Levels, ScopedValue Identities, and Policy Interjectors work as specified.
 */
public class SecurityPolicyVerifyTest {

    @Test
    public void testPolicyEnforcement() throws Exception {
        VNodeRunner node = VNodeRunner.create(0);
        node.start();
        
        try {
            VStreamServiceImpl streamService = (VStreamServiceImpl) node.getContext().getStream();
            node.getRegistry().setTrustLevel("untrusted-plugin", VTrustLevel.SANDBOXED);
            node.getRegistry().setTrustLevel("trusted-plugin", VTrustLevel.RESTRICTED);
            
            // 1. Verify Trust Level Rejection
            // Default "plugin-1" (unknown) is SANDBOXED. 
            // Requesting DIRECT stream (which requires FULL) should fail.
            VFlowPolicy directPolicy = new VFlowPolicy(
                VFlowPolicy.FlowMode.DIRECT, 
                VFlowPolicy.FlowDirection.BIDIRECTIONAL, 
                VTrustLevel.FULL
            );
            
            assertThrows(SecurityException.class, () -> {
                ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, "untrusted-plugin")
                    .run(() -> streamService.createPolicyStream("test-stream-1", directPolicy));
            }, "Should reject DIRECT flow for untrusted plugin");

            // 2. Verify Interjector "Most Restrictive Wins"
            // We add two interjectors: one ALLOWS, one DENIES. Result should be DENY.
            streamService.addInterjector((pluginId, policy) -> VPolicyInterjector.Decision.ALLOW);
            streamService.addInterjector((pluginId, policy) -> VPolicyInterjector.Decision.DENY);

            VFlowPolicy mediatedPolicy = new VFlowPolicy(
                VFlowPolicy.FlowMode.MEDIATED, 
                VFlowPolicy.FlowDirection.INBOUND, 
                VTrustLevel.SANDBOXED
            );

            assertThrows(SecurityException.class, () -> {
                ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, "trusted-plugin")
                    .run(() -> streamService.createPolicyStream("test-stream-2", mediatedPolicy));
            }, "Should reject if any interjector says DENY (Most Restrictive Wins)");

        } finally {
            node.stop();
        }
    }

    @Test
    public void testScopedValueIdentity() throws Exception {
        VNodeRunner node = VNodeRunner.create(0);
        node.start();
        
        try {
            VStreamServiceImpl streamService = (VStreamServiceImpl) node.getContext().getStream();
            node.getRegistry().setTrustLevel("expected-plugin", VTrustLevel.SANDBOXED);
            node.getRegistry().setTrustLevel("wrong-plugin", VTrustLevel.SANDBOXED);
            
            // Verify that the identity is seen by the interjector
            streamService.addInterjector((pluginId, policy) -> {
                if ("expected-plugin".equals(pluginId)) {
                    return VPolicyInterjector.Decision.ALLOW;
                }
                return VPolicyInterjector.Decision.DENY;
            });

            VFlowPolicy policy = VFlowPolicy.DEFAULT;

            assertDoesNotThrow(() -> {
                ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, "expected-plugin")
                    .run(() -> streamService.createPolicyStream("identity-test", policy));
            });

            assertThrows(SecurityException.class, () -> {
                ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, "wrong-plugin")
                    .run(() -> streamService.createPolicyStream("identity-test-fail", policy));
            });

        } finally {
            node.stop();
        }
    }
}
