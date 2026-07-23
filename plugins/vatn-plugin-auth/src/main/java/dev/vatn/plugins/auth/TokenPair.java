package dev.vatn.plugins.auth;

/**
 * A pair of tokens returned after successful login or token refresh.
 *
 * @param accessToken  short-lived JWT for authenticating API requests
 * @param refreshToken long-lived JWT used only to obtain a new {@link TokenPair}
 * @param expiresIn    seconds until the access token expires
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        long expiresIn
) {}
