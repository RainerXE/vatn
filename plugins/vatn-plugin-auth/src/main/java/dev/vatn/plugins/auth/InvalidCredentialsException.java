package dev.vatn.plugins.auth;

/**
 * Thrown by {@link CredentialsValidator} when the supplied username/password are invalid.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }

    public InvalidCredentialsException(String message, Throwable cause) {
        super(message, cause);
    }
}
