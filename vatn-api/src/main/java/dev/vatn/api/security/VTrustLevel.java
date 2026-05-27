package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

/**
 * Trust levels for VATN plugins.
 * Users can set these for specific identities or runtime types.
 */
@VatnApi(since = "1.0")
public enum VTrustLevel {
    /**
     * Completely untrusted. Plugin will not be loaded.
     */
    NONE,

    /**
     * Restricted execution inside a strict sandbox (e.g., WASM with no IO).
     */
    SANDBOXED,

    /**
     * Partially trusted. Can access specific messaging channels but no direct FFI/System IO.
     */
    RESTRICTED,

    /**
     * Fully trusted. Full access to FFI, Memory Channels, and Node Services.
     */
    FULL,

    /**
     * Verified by a known DLT or Federated Authority.
     */
    VERIFIED_FEDERATED
}
