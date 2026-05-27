package dev.vatn.api;

/**
 * Platform-neutral abstraction for a system request.
 * Allows decoupling application logic from specific transport runtimes (Helidon, WASM, etc).
 */
@VatnApi(since = "1.0")
public interface VRequest {
    /**
     * Retrieves a header or metadata value.
     */
    String getHeader(String name);
    
    /**
     * Returns the raw body content.
     */
    String getBody();
    
    /**
     * Returns the source identifier.
     */
    String getSourceId();
}
