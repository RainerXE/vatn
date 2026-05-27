package dev.vatn.api;

/**
 * Enumerates the supported execution environments for VATN plugins.
 * Reflects the "VATN as a Contract" vision for polyglot support.
 */
@VatnApi(since = "1.0")
public enum VRuntimeType {
    /**
     * In-process JVM execution (Standard Java JAR plugins).
     */
    EMBEDDED,

    /**
     * Isolated OS-level process (OIPC v2.12 / UDS interface).
     */
    PROCESS,

    /**
     * High-performance Native interface (Project Panama / FFI).
     */
    FFI,

    /**
     * WebAssembly sandbox (Extism or GraalVM Wasm).
     */
    WASM,

    /**
     * Remote proxy (Gateway/MQTT/Bridge).
     */
    REMOTE
}
