package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

/**
 * Interface for intercepting and approving/rejecting security-sensitive operations.
 * Allows the node runner or custom security plugins to enforce "Most Restrictive Wins" policies.
 */
@VatnApi(since = "1.0")
public interface VPolicyInterjector {

    /**
     * Called when a plugin requests a specific data or control flow.
     * 
     * @param pluginId The identifier of the requesting plugin.
     * @param requestedPolicy The flow policy requested by the plugin.
     * @return Decision indicating if the flow is allowed or rejected.
     */
    Decision onFlowRequest(String pluginId, VFlowPolicy requestedPolicy);

    /**
     * Enumerates the possible decisions an interjector can make.
     */
    public enum Decision {
        /**
         * Explicitly allow the requested flow.
         */
        ALLOW,

        /**
         * Explicitly block the requested flow.
         */
        DENY,

        /**
         * Neutral result; defer to other interjectors or default policy.
         */
        ABSTAIN
    }
}
