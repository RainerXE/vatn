package dev.vatn.core.security;

import dev.vatn.api.security.SecretHolder;
import dev.vatn.api.security.VSecretService;
import dev.vatn.api.VPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

public class VSecretServiceImpl implements VSecretService {
    private static final Logger logger = LoggerFactory.getLogger(VSecretServiceImpl.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;

    private final VPersistenceService db;
    private final SecretKey masterKey;

    public VSecretServiceImpl(VPersistenceService db) {
        this(db, requireEnvMasterKey());
    }

    /**
     * Public constructor for CLI or Test usage — supply the raw key directly.
     */
    public VSecretServiceImpl(VPersistenceService db, String rawMasterKey) {
        this.db = db;
        this.masterKey = deriveKey(rawMasterKey);
        init();
    }

    private void init() {
        try (Connection conn = db.getConnection()) {
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS v_secrets (" +
                "  secret_key TEXT PRIMARY KEY," +
                "  encrypted_payload BLOB," +
                "  iv BLOB," +
                "  updated_at INTEGER" +
                ")"
            );
        } catch (SQLException e) {
            logger.error("Failed to initialize v_secrets table", e);
        }
    }

    /** Reads VATN_MASTER_KEY from the environment or a local key file. */
    private static String requireEnvMasterKey() {
        String rawKey = System.getenv("VATN_MASTER_KEY");
        if (rawKey != null && !rawKey.isBlank()) {
            return rawKey;
        }

        // Fallback to local key file (DCN-102)
        java.nio.file.Path keyPath = java.nio.file.Paths.get(System.getProperty("user.home"), ".vatn", ".master_key");
        try {
            if (java.nio.file.Files.exists(keyPath)) {
                return java.nio.file.Files.readString(keyPath).trim();
            } else {
                logger.info("[SECURITY] No master key found. Generating a new one at {}", keyPath);
                java.security.SecureRandom random = new java.security.SecureRandom();
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String newKey = java.util.Base64.getEncoder().encodeToString(bytes);
                java.nio.file.Files.createDirectories(keyPath.getParent());
                try {
                    java.util.Set<java.nio.file.attribute.PosixFilePermission> ownerOnly =
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
                    if (!java.nio.file.Files.exists(keyPath)) {
                        java.nio.file.Files.createFile(keyPath,
                            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly));
                    } else {
                        java.nio.file.Files.setPosixFilePermissions(keyPath, ownerOnly);
                    }
                    java.nio.file.Files.writeString(keyPath, newKey);
                    logger.info("[SECURITY] Master key written with owner-only permissions: {}", keyPath);
                } catch (UnsupportedOperationException e) {
                    java.nio.file.Files.writeString(keyPath, newKey);
                    logger.warn("[SECURITY] Non-POSIX filesystem — master key written without permission restrictions: {}", keyPath);
                }
                return newKey;
            }
        } catch (Exception e) {
            logger.error("[SECURITY] Failed to manage local master key file", e);
            throw new IllegalStateException(
                "[SECURITY] VATN_MASTER_KEY environment variable is not set and local key generation failed. " +
                "The secret store cannot start. Please set VATN_MASTER_KEY manually.");
        }
    }

    /** Derives an AES-256 key from the given raw string via SHA-256. */
    private static SecretKey deriveKey(String rawKey) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to derive master key", e);
        }
    }

    @Override
    public void storeSecret(String key, SecretHolder secretHolder) {
        byte[] iv = new byte[IV_LENGTH_BYTE];
        new SecureRandom().nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            
            byte[] plainText = secretHolder.reveal().getBytes(StandardCharsets.UTF_8);
            byte[] cipherText = cipher.doFinal(plainText);
            Arrays.fill(plainText, (byte) 0); // Quick zeroize of intermediate byte array

            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO v_secrets (secret_key, encrypted_payload, iv, updated_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, key);
                ps.setBytes(2, cipherText);
                ps.setBytes(3, iv);
                ps.setLong(4, System.currentTimeMillis());
                ps.execute();
            }
        } catch (java.security.GeneralSecurityException | java.sql.SQLException e) {
            logger.error("Failed to store secret: {}", key, e);
            throw new RuntimeException("Storage failure", e);
        }
    }

    @Override
    public SecretHolder getSecret(String key) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT encrypted_payload, iv FROM v_secrets WHERE secret_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] cipherText = rs.getBytes("encrypted_payload");
                    byte[] iv = rs.getBytes("iv");

                    Cipher cipher = Cipher.getInstance(ALGORITHM);
                    cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
                    byte[] plainText = cipher.doFinal(cipherText);

                    // Decode once into a CharBuffer — use limit() not remaining() after the flip
                    java.nio.CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plainText));
                    char[] finalChars = Arrays.copyOf(cb.array(), cb.limit());

                    // Zeroize all intermediate buffers before returning
                    Arrays.fill(plainText, (byte) 0);
                    Arrays.fill(cb.array(), (char) 0);

                    return new SecretHolder(finalChars);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve secret: {}", key, e);
        }
        return null;
    }

    @Override
    public void deleteSecret(String key) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM v_secrets WHERE secret_key = ?")) {
            ps.setString(1, key);
            ps.execute();
        } catch (SQLException e) {
            logger.error("Failed to delete secret: {}", key, e);
        }
    }
}
