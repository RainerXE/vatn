package dev.vatn.api;

/**
 * Common lifecycle channel and message constants for the VATN ecosystem.
 * These are the language-neutral signals used for the plugin handshake.
 */
@VatnApi(since = "1.0")
public final class VLifecycle {

    /**
     * Initialization signal. Sent by the Node to the Plugin.
     */
    public static final String VATN_INIT = "vatn.lifecycle.init";

    /**
     * Ready signal. Sent by the Plugin to the Node once setup is complete.
     */
    public static final String VATN_READY = "vatn.lifecycle.ready";

    /**
     * Shutdown signal. Sent by the Node to the Plugin.
     */
    public static final String VATN_SHUTDOWN = "vatn.lifecycle.shutdown";

    /**
     * Capability Acknowledge. Sent by the Plugin after receiving OIPC greeting.
     */
    public static final String VATN_CAPABILITY_ACK = "vatn.lifecycle.cap_ack";

    private VLifecycle() {}
}
