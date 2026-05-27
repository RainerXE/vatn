package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

import dev.vatn.api.VService;

/**
 * Service for managing encrypted secrets (API keys, tokens).
 */
@VatnApi(since = "1.0")
public interface VSecretService extends VService {

    /**
     * Stores a secret securely.
     */
    void storeSecret(String key, SecretHolder secret);

    /**
     * Retrieves a secret, automatically zeroizing it when closed.
     */
    SecretHolder getSecret(String key);

    /**
     * Removes a secret from storage.
     */
    void deleteSecret(String key);
}
