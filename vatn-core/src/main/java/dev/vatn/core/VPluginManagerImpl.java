package dev.vatn.core;

import dev.vatn.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Runtime implementation of {@link VPluginManager}.
 * Registered as a service by {@link VNodeRunner} after all plugins initialize.
 */
class VPluginManagerImpl implements VPluginManager {

    private static final Logger log = LoggerFactory.getLogger(VPluginManagerImpl.class);

    private final List<VNodePlugin> plugins;
    private final VNodeContext context;
    private final Map<String, PluginState> states = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    VPluginManagerImpl(List<VNodePlugin> plugins, VNodeContext context) {
        this.plugins = plugins;
        this.context = context;
    }

    /** Records a plugin as successfully initialized (called by VNodeRunner after onInitialize). */
    void markRunning(String pluginId) {
        states.put(pluginId, PluginState.RUNNING);
        errors.remove(pluginId);
    }

    /** Records a plugin init failure so getStatuses() surfaces it instead of crashing the node. */
    void markError(String pluginId, Throwable t) {
        states.put(pluginId, PluginState.ERROR);
        errors.put(pluginId, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
    }

    @Override
    public boolean restart(String pluginId) {
        VNodePlugin plugin = findPlugin(pluginId);
        if (plugin == null) return false;
        if (states.get(pluginId) == PluginState.RESTARTING) return false;

        states.put(pluginId, PluginState.RESTARTING);
        Thread.ofVirtual().name("vatn-restart-" + pluginId).start(() -> {
            try {
                ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, pluginId).run(() -> {
                    plugin.onShutdown();
                    plugin.onInitialize(context);
                });
                Thread.ofVirtual().start(() -> {
                    try { plugin.onReady(); } catch (Exception ignored) {}
                });
                states.put(pluginId, PluginState.RUNNING);
                errors.remove(pluginId);
                log.info("Plugin {} restarted successfully", pluginId);
            } catch (Exception e) {
                states.put(pluginId, PluginState.ERROR);
                errors.put(pluginId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                log.error("Plugin {} restart failed", pluginId, e);
            }
        });
        return true;
    }

    @Override
    public boolean stop(String pluginId) {
        VNodePlugin plugin = findPlugin(pluginId);
        if (plugin == null) return false;
        if (states.get(pluginId) == PluginState.STOPPED) return true;

        try {
            ScopedValue.where(VatnSecurity.CURRENT_PLUGIN_ID, pluginId).run(plugin::onShutdown);
            states.put(pluginId, PluginState.STOPPED);
            errors.remove(pluginId);
            log.info("Plugin {} stopped", pluginId);
            return true;
        } catch (Exception e) {
            states.put(pluginId, PluginState.ERROR);
            errors.put(pluginId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.error("Plugin {} stop failed", pluginId, e);
            return false;
        }
    }

    @Override
    public List<PluginStatus> getStatuses() {
        return plugins.stream()
                .map(p -> new PluginStatus(
                        p.getId(), p.getName(), p.getVersion(),
                        states.getOrDefault(p.getId(), PluginState.RUNNING),
                        errors.get(p.getId())))
                .collect(Collectors.toList());
    }

    private VNodePlugin findPlugin(String id) {
        return plugins.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }
}
