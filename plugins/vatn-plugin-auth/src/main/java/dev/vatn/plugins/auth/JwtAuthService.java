package dev.vatn.plugins.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * JJWT-backed implementation of {@link AuthService}.
 *
 * <p>Tokens are signed with HMAC-SHA256. Access tokens carry the custom claims returned by the
 * {@link CredentialsValidator}; refresh tokens carry only subject and type to keep them small.
 */
public class JwtAuthService implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthService.class);

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final AuthConfig config;

    public JwtAuthService(AuthConfig config) {
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // AuthService
    // -------------------------------------------------------------------------

    @Override
    public TokenPair login(String username, String password) {
        Map<String, Object> claims = config.validator().validate(username, password);
        return issueTokenPair(username, claims);
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        Claims payload = parseToken(refreshToken);

        Object type = payload.get(CLAIM_TYPE);
        if (!TYPE_REFRESH.equals(type)) {
            throw new AuthenticationException("Token is not a refresh token");
        }

        String subject = payload.getSubject();
        return issueTokenPair(subject, Map.of());
    }

    @Override
    public AuthContext authenticate(String bearerToken) {
        Claims payload = parseToken(bearerToken);

        Object type = payload.get(CLAIM_TYPE);
        if (!TYPE_ACCESS.equals(type)) {
            throw new AuthenticationException("Token is not an access token");
        }

        return buildAuthContext(payload);
    }

    @Override
    public Optional<AuthContext> tryAuthenticate(String bearerToken) {
        try {
            return Optional.of(authenticate(bearerToken));
        } catch (AuthenticationException e) {
            log.debug("Token authentication failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private TokenPair issueTokenPair(String subject, Map<String, Object> extraClaims) {
        String accessToken = buildAccessToken(subject, extraClaims);
        String refreshToken = buildRefreshToken(subject);
        return new TokenPair(accessToken, refreshToken, config.accessTokenTtlSeconds());
    }

    private String buildAccessToken(String subject, Map<String, Object> extraClaims) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuer(config.issuer())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + config.accessTokenTtlSeconds() * 1000L))
                .claim(CLAIM_TYPE, TYPE_ACCESS);

        for (var entry : extraClaims.entrySet()) {
            builder.claim(entry.getKey(), entry.getValue());
        }

        return builder.signWith(buildKey()).compact();
    }

    private String buildRefreshToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuer(config.issuer())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + config.refreshTokenTtlSeconds() * 1000L))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .signWith(buildKey())
                .compact();
    }

    private Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(buildKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new AuthenticationException("Invalid or expired token: " + e.getMessage(), e);
        }
    }

    private AuthContext buildAuthContext(Claims payload) {
        Map<String, Object> claims = new HashMap<>(payload);
        // Remove standard JWT claims from the custom-claims map
        claims.remove(Claims.SUBJECT);
        claims.remove(Claims.ISSUER);
        claims.remove(Claims.ISSUED_AT);
        claims.remove(Claims.EXPIRATION);
        claims.remove(CLAIM_TYPE);

        Instant expiresAt = payload.getExpiration() != null
                ? payload.getExpiration().toInstant()
                : Instant.MAX;

        return new AuthContext(payload.getSubject(), Map.copyOf(claims), expiresAt);
    }

    private SecretKey buildKey() {
        return Keys.hmacShaKeyFor(config.secret().getBytes(StandardCharsets.UTF_8));
    }
}
