package dev.vatn.core.security;

import java.security.PublicKey;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native Java implementation of Ed25519 signature verification using EdDSA.
 * Aligned with VATN Phase 3 security requirements.
 */
public class VPackageVerifier {

    private static final Logger logger = LoggerFactory.getLogger(VPackageVerifier.class);
    private static final String ALGORITHM = "Ed25519";

    /**
     * Verifies that the data matches the signature provided by the public key.
     * 
     * @param data The raw data (e.g. zip file bytes)
     * @param signatureBase64 The base64 encoded Ed25519 signature
     * @param publicKeyBase64 The base64 encoded Ed25519 public key
     * @return true if valid
     */
    public boolean verify(byte[] data, String signatureBase64, String publicKeyBase64) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);

            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(keyBytes));

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(pubKey);
            sig.update(data);

            return sig.verify(signatureBytes);
        } catch (Exception e) {
            logger.error("[SECURITY] Package verification failed", e);
            return false;
        }
    }
}
