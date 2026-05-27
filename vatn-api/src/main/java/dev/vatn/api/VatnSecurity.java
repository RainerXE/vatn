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

    private VatnSecurity() {}
}
