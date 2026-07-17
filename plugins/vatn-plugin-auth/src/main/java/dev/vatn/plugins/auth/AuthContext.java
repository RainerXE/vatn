package dev.vatn.plugins.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * The security context decoded from a valid access token.
 *
 * @param subject   the JWT {@code sub} claim — typically the username or user ID
 * @param claims    custom claims embedded when the token was issued (e.g. {@code role}, {@code userId})
 * @param expiresAt the instant at which the access token expires
 */
public record AuthContext(
        String subject,
        Map<String, Object> claims,
        Instant expiresAt
) {
    /**
     * Returns {@code true} if the {@code role} claim equals the given role or,
     * if the claim is a collection, the collection contains the role.
     */
    public boolean hasRole(String role) {
        Object r = claims.get("role");
        if (r == null) {
            return false;
        }
        if (role.equals(r)) {
            return true;
        }
        if (r instanceof Collection<?> c) {
            return c.contains(role);
        }
        return false;
    }

    /**
     * Returns the claim value as a {@link String}, or {@code null} if the claim is absent.
     */
    public String getStringClaim(String key) {
        Object value = claims.get(key);
        return value == null ? null : value.toString();
    }
}
