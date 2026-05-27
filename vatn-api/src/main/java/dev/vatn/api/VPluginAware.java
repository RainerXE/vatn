package dev.vatn.api;

/**
 * Interface for components that need to be aware of the VATN context.
 * Useful for plugins or agents that are instantiated by the node and need
 * to access shared services.
 */
@VatnApi(since = "1.0")
public interface VPluginAware {
    
    /**
     * Injects the node context into the component.
     * @param context The VATN node context.
     */
    void setContext(VNodeContext context);
}
