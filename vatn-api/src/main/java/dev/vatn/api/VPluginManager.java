package dev.vatn.api;

import java.util.List;

/**
 * Runtime plugin lifecycle management — restart stuck or failed plugins without
 * restarting the entire node.
 *
 * <p>Registered automatically by the VATN runtime. Obtain via:
 * <pre>{@code
 * ctx.getService(VPluginManager.class).ifPresent(mgr -> {
 *     mgr.restart("dev.vatn.plugins.postgres");
 * });
 * }</pre>
 *
 * <p><b>Important:</b> HTTP routes registered by a plugin are wired into the
 * server at startup and cannot be refreshed at runtime. Restart is most effective
 * for infrastructure plugins (database pools, Redis, email, AI clients) that
 * register services rather than HTTP routes.
 */
@VatnApi(since = "1.0-alpha.8")
public interface VPluginManager extends VService {

    enum PluginState { RUNNING, RESTARTING, STOPPED, ERROR }

    record PluginStatus(
            String      id,
            String      name,
            String      version,
            PluginState state,
            String      lastError   // null unless state == ERROR
    ) {}

    /**
     * Restarts a plugin: calls {@code onShutdown()} then {@code onInitialize()}.
     * The operation runs asynchronously on a virtual thread; the method returns
     * {@code true} immediately if the plugin was found, {@code false} if not found
     * or if a restart is already in progress.
     */
    boolean restart(String pluginId);

    /**
     * Stops a plugin by calling {@code onShutdown()} and marking it STOPPED.
     * The plugin remains registered and can be restarted later via {@link #restart}.
     */
    boolean stop(String pluginId);

    /** Live status snapshot for all registered plugins. */
    List<PluginStatus> getStatuses();
}
