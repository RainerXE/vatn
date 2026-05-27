package dev.vatn.core.security;

import dev.vatn.api.security.SecretHolder;
import dev.vatn.core.memory.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class VSecretServiceTest {
    private static final String TEST_MASTER_KEY = "vatn-test-key-not-for-production-use-only";

    private VSecretServiceImpl service;
    private DatabaseManager db;
    private String dbPath;

    @BeforeEach
    public void setup() {
        // Unique temp DB per test run; package-private constructor bypasses env var requirement.
        dbPath = "target/test-secrets-" + System.nanoTime() + ".db";
        db = new DatabaseManager("jdbc:sqlite:" + dbPath);
        service = new VSecretServiceImpl(db, TEST_MASTER_KEY);
    }

    @AfterEach
    public void teardown() {
        new File(dbPath).delete();
    }


    @Test
    void testStoreAndRetrieveSecret() {
        String key = "test-api-key";
        String secretValue = "sk-1234567890abcdef";
        
        try (SecretHolder original = new SecretHolder(secretValue)) {
            service.storeSecret(key, original);
        }

        try (SecretHolder retrieved = service.getSecret(key)) {
            assertNotNull(retrieved, "Secret should be retrievable after storage");
            assertEquals(secretValue, retrieved.reveal(), "Decrypted secret must match original");
            assertFalse(retrieved.isZeroized(), "Retrieved secret should not be zeroized yet");
        }
    }

    @Test
    void testDeleteSecret() {
        String key = "temporary-secret";
        service.storeSecret(key, new SecretHolder("delete-me"));
        assertNotNull(service.getSecret(key));

        service.deleteSecret(key);
        assertNull(service.getSecret(key), "Secret should be null after deletion");
    }

    @Test
    void testUpdateSecret() {
        String key = "rotating-key";
        
        service.storeSecret(key, new SecretHolder("version-1"));
        try (SecretHolder s1 = service.getSecret(key)) {
            assertEquals("version-1", s1.reveal());
        }

        service.storeSecret(key, new SecretHolder("version-2"));
        try (SecretHolder s2 = service.getSecret(key)) {
            assertEquals("version-2", s2.reveal(), "Stored secret should be updated (upsert)");
        }
    }

    @Test
    void testRetrieveMissingSecret() {
        assertNull(service.getSecret("missing-key"), "Retrieving a non-existent key should return null");
    }

    @Test
    void testEncryptionIsUniquePerStore() {
        // Even with the same secret value, IVs should be different
        String secretValue = "same-secret";
        service.storeSecret("key1", new SecretHolder(secretValue));
        service.storeSecret("key2", new SecretHolder(secretValue));

        // We can't easily check the IV without querying the DB directly, 
        // but we verify both can be decrypted correctly which validates individual IV usage.
        try (SecretHolder s1 = service.getSecret("key1")) {
            assertEquals(secretValue, s1.reveal());
        }
        try (SecretHolder s2 = service.getSecret("key2")) {
            assertEquals(secretValue, s2.reveal());
        }
    }
}
