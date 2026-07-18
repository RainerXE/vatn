package dev.vatn.api;

/**
 * Internal security utilities for the VATN layer.
 * Uses Java 25 ScopedValues for secure identity propagation across virtual threads.
 */
@VatnApi(since = "1.0")
public final class VatnSecurity {

    /**
     * The unique identifier of the plugin currently executing in the thread scope.
     */
    public static final ScopedValue<String> CURRENT_PLUGIN_ID = ScopedValue.newInstance();

    /**
     * The dynamic trust level applied to the current execution scope.
     */
    public static final ScopedValue<dev.vatn.api.security.VTrustLevel> CURRENT_TRUST_LEVEL = ScopedValue.newInstance();

    /**
     * The v2.13 per-connection auth token (24-byte identity), or null if not presented.
     */
    public static final ScopedValue<byte[]> CURRENT_AUTH_TOKEN = ScopedValue.newInstance();

    /**
     * The v2.13 per-connection stable client id (16 bytes), or null if not presented.
     */
    public static final ScopedValue<byte[]> CURRENT_CLIENT_ID = ScopedValue.newInstance();

    private VatnSecurity() {}
}
