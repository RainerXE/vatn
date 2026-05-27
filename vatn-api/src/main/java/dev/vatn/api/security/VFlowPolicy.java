package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

/**
 * Defines the security policy for a specific data or control flow.
 * Governs how plugins can stream data or send messages.
 */
@VatnApi(since = "1.0")
public record VFlowPolicy(
    FlowMode mode,
    FlowDirection direction,
    VTrustLevel requiredTrust
) {
    
    /**
     * Execution mode of the flow.
     */
    public enum FlowMode {
        /**
         * Flow is mediated by the Node Controller (Audited/Filtered).
         */
        MEDIATED,

        /**
         * Flow is establish directly between peers for maximum performance.
         */
        DIRECT
    }

    /**
     * Directional permisions for the flow.
     */
    public enum FlowDirection {
        INBOUND,
        OUTBOUND,
        BIDIRECTIONAL
    }

    /**
     * Default policy: Mediated, Inbound-only, Sandboxed trust.
     */
    public static final VFlowPolicy DEFAULT = new VFlowPolicy(
        FlowMode.MEDIATED, 
        FlowDirection.INBOUND, 
        VTrustLevel.SANDBOXED
    );
}
