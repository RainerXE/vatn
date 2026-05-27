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
     * Called when the node is stopping.
     */
    default void onShutdown() {}
}
