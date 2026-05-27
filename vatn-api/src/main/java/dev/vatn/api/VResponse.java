package dev.vatn.api;

/**
 * Platform-neutral abstraction for a system response.
 */
@VatnApi(since = "1.0")
public interface VResponse {
    /**
     * Sets a header or metadata value.
     */
    void setHeader(String name, String value);
    
    /**
     * Sends the final response content.
     */
    void send(String content);
    
    /**
     * Sets the status code.
     */
    void setStatus(int code);
}
