package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

/**
 * Lean rule-based firewall for VATN plugins.
 * Controls access to specific actions, messaging channels, or memory regions.
 */
@VatnApi(since = "1.0")
public interface VFirewall {
    
    /**
     * Checks if a specific action or resource access is allowed for the given plugin.
     * 
     * @param pluginId The identifier of the plugin requesting access.
     * @param permission The permission string (e.g., "io.system.read", "message.broadcast").
     * @return true if allowed, false otherwise.
     */
    boolean isAllowed(String pluginId, String permission);

    /**
     * Called during runtime to update rules dynamically.
     */
    void updateRule(String pluginId, String permission, boolean allow);

    /**
     * Checks if the provided text contains any SSRF attempts.
     * This allows it to be used as a platform-level ingress filter.
     * 
     * @param text The text to scan (e.g., tool arguments, JSON, URLs).
     * @return true if a possible SSRF attempt is detected.
     */
    default boolean isSsrfAttempt(String text) {
        return false;
    }
}
