package dev.vatn.plugins.auth;

/**
 * Immutable configuration for the JWT auth plugin.
 *
 * @param secret               HMAC-SHA256 signing key — minimum 32 characters
 * @param accessTokenTtlSeconds  lifetime of an access token in seconds (default 3600 = 1 hour)
 * @param refreshTokenTtlSeconds lifetime of a refresh token in seconds (default 604800 = 7 days)
 * @param issuer               JWT {@code iss} claim value (default {@code "vatn"})
 * @param validator            strategy that validates username/password and returns custom claims
 */
public record AuthConfig(
        String secret,
        long accessTokenTtlSeconds,
        long refreshTokenTtlSeconds,
        String issuer,
        CredentialsValidator validator
) {
    public AuthConfig {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "AuthConfig.secret must be at least 32 characters (HMAC-SHA256 requirement)");
        }
        if (accessTokenTtlSeconds <= 0) {
            throw new IllegalArgumentException("accessTokenTtlSeconds must be positive");
        }
        if (refreshTokenTtlSeconds <= 0) {
            throw new IllegalArgumentException("refreshTokenTtlSeconds must be positive");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }
    }

    /**
     * Convenience factory with sensible defaults (1 h access token, 7 day refresh token,
     * issuer {@code "vatn"}).
     *
     * @param secret    signing key — minimum 32 characters
     * @param validator credentials validation strategy
     */
    public static AuthConfig of(String secret, CredentialsValidator validator) {
        return new AuthConfig(secret, 3600, 604800, "vatn", validator);
    }
}
