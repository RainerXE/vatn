package dev.vatn.plugins.activitypub;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Configuration for the ActivityPub plugin.
 *
 * <pre>{@code
 * // Generate a new key pair (do this once, persist the PEM strings)
 * ActivityPubConfig.KeyPairPem keys = ActivityPubConfig.generateKeyPair();
 * System.out.println(keys.privateKeyPem());
 * System.out.println(keys.publicKeyPem());
 *
 * // Create config with existing keys
 * ActivityPubConfig config = ActivityPubConfig.of("example.com", "node", keys.privateKeyPem(), keys.publicKeyPem());
 * }</pre>
 */
public final class ActivityPubConfig {

    private final String domain;
    private final String username;
    private final String privateKeyPem;
    private final String publicKeyPem;
    private final boolean https;

    private ActivityPubConfig(String domain, String username, String privateKeyPem, String publicKeyPem, boolean https) {
        this.domain        = domain;
        this.username      = username;
        this.privateKeyPem = privateKeyPem;
        this.publicKeyPem  = publicKeyPem;
        this.https         = https;
    }

    public static ActivityPubConfig of(String domain, String username, String privateKeyPem, String publicKeyPem) {
        return new ActivityPubConfig(domain, username, privateKeyPem, publicKeyPem, true);
    }

    public ActivityPubConfig withHttp() {
        return new ActivityPubConfig(domain, username, privateKeyPem, publicKeyPem, false);
    }

    public String getDomain()        { return domain; }
    public String getUsername()      { return username; }
    public String getPrivateKeyPem() { return privateKeyPem; }
    public String getPublicKeyPem()  { return publicKeyPem; }
    public boolean isHttps()         { return https; }

    public String getBaseUrl() {
        return (https ? "https" : "http") + "://" + domain;
    }

    public String getActorUrl()   { return getBaseUrl() + "/ap/actor"; }
    public String getInboxUrl()   { return getBaseUrl() + "/ap/inbox"; }
    public String getOutboxUrl()  { return getBaseUrl() + "/ap/outbox"; }
    public String getKeyId()      { return getActorUrl() + "#main-key"; }

    PrivateKey loadPrivateKey() {
        try {
            String stripped = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA private key", e);
        }
    }

    PublicKey loadPublicKey() {
        try {
            String stripped = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA public key", e);
        }
    }

    /** Generates a fresh RSA-2048 key pair. Call once and persist both PEM strings. */
    public static KeyPairPem generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            java.security.KeyPair kp = gen.generateKeyPair();

            Base64.Encoder enc = Base64.getMimeEncoder(64, new byte[]{'\n'});

            String privatePem = "-----BEGIN PRIVATE KEY-----\n"
                    + enc.encodeToString(kp.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";

            String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                    + enc.encodeToString(kp.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----";

            return new KeyPairPem(privatePem, publicPem);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available", e);
        }
    }

    public record KeyPairPem(String privateKeyPem, String publicKeyPem) {}
}
