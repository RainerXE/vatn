package dev.vatn.plugins.bcrypt;

import dev.vatn.api.VService;

/**
 * Password hashing service registered by {@link BcryptPlugin}.
 *
 * <pre>{@code
 * BcryptService bcrypt = ctx.getService(BcryptService.class).orElseThrow();
 *
 * // On registration
 * String hash = bcrypt.hash(rawPassword);
 * userRepo.save(new User(username, hash));
 *
 * // On login — pass as the CredentialsValidator to AuthPlugin
 * AuthConfig auth = AuthConfig.of(secret, (user, pass) -> {
 *     String stored = userRepo.findHash(user);
 *     if (!bcrypt.verify(pass, stored)) throw new InvalidCredentialsException("bad password");
 *     return Map.of("role", userRepo.findRole(user));
 * });
 * }</pre>
 */
public interface BcryptService extends VService {

    /** Hashes {@code plaintext} with the configured work factor. */
    String hash(String plaintext);

    /**
     * Returns {@code true} if {@code plaintext} matches the stored {@code hash}.
     * Safe against timing attacks.
     */
    boolean verify(String plaintext, String hash);
}
