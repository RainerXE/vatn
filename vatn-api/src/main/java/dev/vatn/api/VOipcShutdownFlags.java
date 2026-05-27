package dev.vatn.api;

/**
 * Flag constants for the OIPC SHUTDOWN (0x0B) message type.
 */
@VatnApi(since = "1.0")
public final class VOipcShutdownFlags {

    /**
     * Relaxed Shutdown (0x00).
     * Fire-and-forget; client closes immediately after sending.
     */
    public static final int RELAXED = 0x00;

    /**
     * Strict Shutdown (0x01).
     * Request acknowledgment; client waits for ACK (0x05) before closing.
     */
    public static final int STRICT = 0x01;

    private VOipcShutdownFlags() {}
}
