package dev.vatn.plugins.auth;

/**
 * Thrown when a JWT token cannot be authenticated — expired, malformed, wrong signature, etc.
 */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
