package dev.vatn.api;

/**
 * Base SPI for any plugin in the VATN (Virtual Application Transaction Node) ecosystem.
 */
@VatnApi(since = "1.0")
public interface VNodePlugin {
    
    /**
     * Unique identifier for the plugin (e.g., "com.example.search-provider").
     */
    String getId();
    
    /**
     * Human-readable name.
     */
    String getName();
    
    /**
     * Version of the plugin.
     */
    String getVersion();

    /**
     * Called during node initialization.
     * @param context Provides access to node-level services (Memory, Messaging).
     */
    default void onInitialize(VNodeContext context) {}

    /**
     * Called once all plugins have been initialized and the HTTP server is
     * fully bound and accepting connections. Use this to perform work that
     * depends on other plugins being ready (e.g. looking up a service
     * registered by a sibling plugin).
     */
    default void onReady() {}

    /**
     * Called when the node's configuration has been refreshed (e.g. a config file was reloaded
     * or an environment variable changed). Plugins that cache config values should re-read them here.
     */
    default void onConfigReloaded() {}

    /**
     * Called when the node is stopping.
     */
    default void onShutdown() {}
}
