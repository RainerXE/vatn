package dev.vatn.core;

import dev.vatn.api.VNodeIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of VNodeIdentity using Ed25519 (EdDSA).
 * Supports persistent keys stored in human-readable Base64 format.
 */
public class VNodeIdentityImpl implements VNodeIdentity {
    private static final Logger logger = LoggerFactory.getLogger(VNodeIdentityImpl.class);
    private static final String ALGORITHM = "Ed25519";
    
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String nodeId;
    
    private final Map<String, PublicKey> remoteKeyCache = new ConcurrentHashMap<>();

    public VNodeIdentityImpl() throws Exception {
        this(Paths.get(System.getProperty("user.home"), ".vatn", "identity.pem"));
    }

    public VNodeIdentityImpl(Path keyPath) throws Exception {
        if (Files.exists(keyPath)) {
            logger.info("[SECURITY] Loading persistent identity from {}", keyPath);
            KeyPair kp = loadKeyPair(keyPath);
            this.privateKey = kp.getPrivate();
            this.publicKey = kp.getPublic();
        } else {
            logger.info("[SECURITY] No identity found. Generating new Ed25519 keys...");
            KeyPair kp = generateKeyPair();
            saveKeyPair(kp, keyPath);
            this.privateKey = kp.getPrivate();
            this.publicKey = kp.getPublic();
        }
        this.nodeId = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        logger.info("[SECURITY] Node Identity established: {}", nodeId);
    }

    private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
        return kpg.generateKeyPair();
    }

    private void saveKeyPair(KeyPair kp, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        String encoded = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n" +
                         Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        // Attempt POSIX owner-only permissions (Linux, macOS)
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            if (!Files.exists(path)) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerOnly));
            } else {
                Files.setPosixFilePermissions(path, ownerOnly);
            }
            Files.writeString(path, encoded);
            logger.info("[SECURITY] Identity key written with owner-only permissions: {}", path);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows NTFS) — write then mark hidden
            Files.writeString(path, encoded);
            try {
                DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class);
                if (view != null) view.setHidden(true);
            } catch (Exception hiddenEx) {
                logger.warn("[SECURITY] Could not set hidden attribute on key file: {}", path, hiddenEx);
            }
            logger.warn("[SECURITY] Non-POSIX filesystem detected — key file written without permission restrictions. " +
                        "Manually restrict access to: {}", path);
        }
    }

    private KeyPair loadKeyPair(Path path) throws Exception {
        String content = Files.readString(path);
        String[] lines = content.split("\n");
        byte[] privBytes = Base64.getDecoder().decode(lines[0].trim());
        byte[] pubBytes = Base64.getDecoder().decode(lines[1].trim());

        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
        return new KeyPair(pub, priv);
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public byte[] sign(byte[] payload) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(payload);
            return sig.sign();
        } catch (Exception e) {
            throw new RuntimeException("Lattice signing failed", e);
        }
    }

    @Override
    public boolean verify(String remoteNodeId, byte[] payload, byte[] signature) {
        try {
            PublicKey remoteKey = remoteKeyCache.computeIfAbsent(remoteNodeId, id -> {
                try {
                    byte[] pubBytes = Base64.getDecoder().decode(id);
                    KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
                    return kf.generatePublic(new X509EncodedKeySpec(pubBytes));
                } catch (Exception e) {
                    logger.warn("[SECURITY] Malformed remote NodeID: {}", id);
                    return null;
                }
            });

            if (remoteKey == null) return false;

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(remoteKey);
            sig.update(payload);
            return sig.verify(signature);
        } catch (Exception e) {
            logger.warn("[SECURITY] Verification failure for node {}: {}", remoteNodeId, e.getMessage());
            return false;
        }
    }

    @Override
    public byte[] getPublicKey() {
        return publicKey.getEncoded();
    }
}
