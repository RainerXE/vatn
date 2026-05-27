package dev.vatn.core.security;

import dev.vatn.api.security.VFirewall;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lean, rule-based implementation of the V-Firewall.
 * Manages plugin permissions with minimal overhead.
 */
public class VFirewallImpl implements VFirewall {
    
    // Simple rule map: PluginID -> (Permission -> Allowed)
    private final Map<String, Map<String, Boolean>> rules = new ConcurrentHashMap<>();

    @Override
    public boolean isAllowed(String pluginId, String permission) {
        Map<String, Boolean> pluginRules = rules.get(pluginId);
        if (pluginRules == null) {
            // Default: If no rules exist, we deny everything (Strict Baseline)
            return false;
        }
        return pluginRules.getOrDefault(permission, false);
    }

    @Override
    public void updateRule(String pluginId, String permission, boolean allow) {
        rules.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>())
             .put(permission, allow);
    }

    /**
     * Resets rules for a specific plugin.
     */
    public void clearRules(String pluginId) {
        rules.remove(pluginId);
    }
}
