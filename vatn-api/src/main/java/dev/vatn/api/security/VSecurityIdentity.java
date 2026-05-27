package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

import java.util.Optional;

/**
 * Represents a cryptographic identity in the VATN ecosystem.
 * Used for the "V-Chain of Trust" to prevent plugin poisoning.
 */
@VatnApi(since = "1.0")
public interface VSecurityIdentity {
    
    /**
     * The unique public key ID.
     */
    String getKeyId();
    
    /**
     * Verifies if a given byte payload matches a signature using this identity.
     */
    boolean verify(byte[] payload, byte[] signature);
    
    /**
     * Returns the 'Trust Level' assigned to this identity by the user.
     */
    VTrustLevel getTrustLevel();

    /**
     * Optional: The Federated source of this identity (if any).
     */
    Optional<String> getFederationSource();
}
