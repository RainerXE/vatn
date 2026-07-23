package dev.vatn.plugins.auth;

import dev.vatn.api.VService;

import java.util.Optional;

/**
 * Service interface for JWT-based authentication.
 *
 * <p>Register an implementation via {@code ctx.registerService(AuthService.class, impl)}.
 * Retrieve it from another plugin via {@code ctx.getService(AuthService.class)}.
 *
 * <p>Extends {@link VService} so it participates in the VATN service registry.
 */
public interface AuthService extends VService {

    /**
     * Validates the supplied credentials and issues a new token pair.
     *
     * @param username the username to authenticate
     * @param password the plaintext password
     * @return a fresh {@link TokenPair}
     * @throws InvalidCredentialsException if the credentials are invalid
     */
    TokenPair login(String username, String password);

    /**
     * Exchanges a valid refresh token for a new token pair.
     *
     * @param refreshToken a previously issued refresh token
     * @return a fresh {@link TokenPair}
     * @throws AuthenticationException if the refresh token is invalid or expired
     */
    TokenPair refresh(String refreshToken);

    /**
     * Validates an access token and returns the decoded {@link AuthContext}.
     *
     * @param bearerToken the raw token string (without {@code "Bearer "} prefix)
     * @return the decoded auth context
     * @throws AuthenticationException if the token is invalid or expired
     */
    AuthContext authenticate(String bearerToken);

    /**
     * Like {@link #authenticate(String)} but returns an empty {@link Optional} instead of
     * throwing when the token is invalid, making it suitable for optional authentication.
     *
     * @param bearerToken the raw token string
     * @return an {@link Optional} containing the auth context, or empty if the token is invalid
     */
    Optional<AuthContext> tryAuthenticate(String bearerToken);
}
