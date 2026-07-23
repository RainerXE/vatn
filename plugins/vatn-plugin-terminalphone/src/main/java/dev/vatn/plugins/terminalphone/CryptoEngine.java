package dev.vatn.plugins.terminalphone;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

class CryptoEngine {

    private static final String PBKDF2_ALG = "PBKDF2WithHmacSHA256";
    private static final int    ITERATIONS  = 100_000;
    private static final byte[] SALT        = "vatn-terminalphone-v1".getBytes();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;
    private final String        cipherSpec;
    private final SecureRandom  rng = new SecureRandom();

    CryptoEngine(String sharedSecret, String cipher) throws Exception {
        this.cipherSpec = toCipherSpec(cipher);
        byte[] material = deriveKey(sharedSecret, SALT, 64);
        this.aesKey  = new SecretKeySpec(Arrays.copyOfRange(material, 0, 32),  "AES");
        this.hmacKey = new SecretKeySpec(Arrays.copyOfRange(material, 32, 64), "HmacSHA256");
    }

    byte[] encrypt(byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(cipherSpec);
        byte[] iv = new byte[16];
        rng.nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] result = new byte[16 + encrypted.length];
        System.arraycopy(iv, 0, result, 0, 16);
        System.arraycopy(encrypted, 0, result, 16, encrypted.length);
        return result;
    }

    byte[] decrypt(byte[] ciphertext) throws Exception {
        if (ciphertext.length < 17) throw new IllegalArgumentException("Ciphertext too short");
        byte[] iv  = Arrays.copyOfRange(ciphertext, 0, 16);
        byte[] enc = Arrays.copyOfRange(ciphertext, 16, ciphertext.length);
        Cipher cipher = Cipher.getInstance(cipherSpec);
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
        return cipher.doFinal(enc);
    }

    byte[] sign(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        return mac.doFinal(data);
    }

    boolean verify(byte[] data, byte[] expectedHmac) throws Exception {
        return MessageDigest.isEqual(sign(data), expectedHmac);
    }

    private byte[] deriveKey(String password, byte[] salt, int lengthBytes) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, lengthBytes * 8);
        return SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).getEncoded();
    }

    private String toCipherSpec(String cipher) {
        return switch (cipher.toUpperCase().replace("_", "-")) {
            case "AES-256-GCM" -> "AES/GCM/NoPadding";
            case "AES-256-CTR" -> "AES/CTR/NoPadding";
            default            -> "AES/CBC/PKCS5Padding";
        };
    }
}
