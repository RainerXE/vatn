package dev.vatn.plugins.auth;

import java.util.Map;

/**
 * Strategy for validating a username/password pair.
 *
 * <p>Implement this interface to plug in any credential store — an in-memory map,
 * a database table, an LDAP directory, etc. The returned map becomes the set of
 * custom claims embedded in the issued JWT.
 *
 * <pre>{@code
 * CredentialsValidator validator = (username, password) -> {
 *     User user = userRepository.findByUsername(username)
 *         .orElseThrow(() -> new InvalidCredentialsException("Unknown user: " + username));
 *     if (!passwordEncoder.matches(password, user.getPasswordHash())) {
 *         throw new InvalidCredentialsException("Wrong password");
 *     }
 *     return Map.of("role", user.getRole(), "userId", user.getId());
 * };
 * }</pre>
 */
@FunctionalInterface
public interface CredentialsValidator {

    /**
     * Validates the supplied credentials.
     *
     * @param username the username presented by the client
     * @param password the plaintext password presented by the client
     * @return a map of custom claims to embed in the JWT (may be empty, never {@code null})
     * @throws InvalidCredentialsException if the credentials are invalid
     */
    Map<String, Object> validate(String username, String password)
            throws InvalidCredentialsException;
}
