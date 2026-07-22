package dev.vatn.plugins.auth;

/** Thrown when a request body cannot be parsed and the client is at fault (HTTP 400). */
class BadRequestException extends RuntimeException {

    BadRequestException(String message) {
        super(message);
    }

    BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
