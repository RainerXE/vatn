package dev.vatn.api;

/**
 * Marker interface for all VATN node services.
 * Services registered in the VNodeContext must implement this interface
 * to be discoverable by plugins.
 */
@VatnApi(since = "1.0")
public interface VService {
}
