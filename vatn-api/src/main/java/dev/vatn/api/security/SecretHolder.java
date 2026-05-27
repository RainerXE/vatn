package dev.vatn.api.security;

import dev.vatn.api.VatnApi;

import java.util.Arrays;
import java.util.function.Function;

/**
 * A container for sensitive data (API keys, passwords) that ensures the secret
 * can be wiped from memory after use.
 */
@VatnApi(since = "1.0")
public final class SecretHolder implements AutoCloseable {
    private char[] secret;

    public SecretHolder(char[] secret) {
        this.secret = secret != null ? Arrays.copyOf(secret, secret.length) : new char[0];
    }

    public SecretHolder(String secret) {
        this.secret = secret != null ? secret.toCharArray() : new char[0];
    }

    /**
     * Executes a function with the raw secret and then returns the result.
     * Use this to temporarily expose the secret to an SDK or HTTP client.
     */
    public <T> T use(Function<char[], T> function) {
        if (secret == null) {
            throw new IllegalStateException("Secret has been zeroized");
        }
        return function.apply(secret);
    }

    /**
     * Safely returns the secret as a String. 
     * NOTE: This creates a String on the heap. Use only at the last possible millisecond.
     */
    public String reveal() {
        if (secret == null) {
            throw new IllegalStateException("Secret has been zeroized");
        }
        return new String(secret);
    }

    /**
     * Overwrites the secret with zeros.
     */
    public void zeroize() {
        if (secret != null) {
            Arrays.fill(secret, (char) 0);
            secret = null;
        }
    }

    @Override
    public void close() {
        zeroize();
    }
    
    public boolean isZeroized() {
        return secret == null;
    }
}
