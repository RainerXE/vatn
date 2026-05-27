package dev.vatn.api;

/**
 * Represents the cryptographic identity of a VATN node.
 * Uses Ed25519 for high-performance, compact signatures.
 */
@VatnApi(since = "1.0")
public interface VNodeIdentity extends VService {

    /**
     * Returns the unique Node ID based on the public key.
     */
    String getNodeId();

    /**
     * Signs a payload using the node's private key.
     */
    byte[] sign(byte[] payload);

    /**
     * Verifies a signature against a payload using a remote node's public key (Node ID).
     */
    boolean verify(String remoteNodeId, byte[] payload, byte[] signature);

    /**
     * Returns the raw public key bytes.
     */
    byte[] getPublicKey();
}
