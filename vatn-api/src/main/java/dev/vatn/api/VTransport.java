package dev.vatn.api;

/**
 * Supported messaging transport types in VATN.
 */
@VatnApi(since = "1.0")
public enum VTransport {
    /**
     * In-memory local transport (non-persistent, JVM only).
     */
    LOCAL,

    /**
     * Unix Domain Sockets (OIPC v2.12 aligned).
     */
    UDS,

    /**
     * TCP Sockets (legacy or external bridge).
     */
    TCP,

    /**
     * Encrypted WebSocket / HTTPS bridge.
     */
    SECURE_TUNNEL,

    /**
     * IoT / Edge sensor gateway (Message Queuing Telemetry Transport).
     */
    MQTT
}
