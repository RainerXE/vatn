package dev.vatn.core.security;

import dev.vatn.api.security.VSecurityIdentity;
import dev.vatn.api.security.VTrustLevel;

import java.security.Signature;
import java.security.PublicKey;
import java.util.Optional;

/**
 * Implementation of the V-Chain of Trust Identity.
 * Uses standard RSA signatures to verify plugin integrity.
 */
public class VSecurityIdentityImpl implements VSecurityIdentity {
    
    private final String keyId;
    private final PublicKey publicKey;
    private VTrustLevel trustLevel;
    private final String federationSource;

    public VSecurityIdentityImpl(String keyId, PublicKey publicKey, VTrustLevel trustLevel, String federationSource) {
        this.keyId = keyId;
        this.publicKey = publicKey;
        this.trustLevel = trustLevel;
        this.federationSource = federationSource;
    }

    @Override
    public String getKeyId() {
        return keyId;
    }

    @Override
    public boolean verify(byte[] payload, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(payload);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public VTrustLevel getTrustLevel() {
        return trustLevel;
    }

    public void setTrustLevel(VTrustLevel trustLevel) {
        this.trustLevel = trustLevel;
    }

    @Override
    public Optional<String> getFederationSource() {
        return Optional.ofNullable(federationSource);
    }
}
