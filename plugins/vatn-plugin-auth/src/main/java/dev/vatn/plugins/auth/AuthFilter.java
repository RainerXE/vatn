package dev.vatn.plugins.auth;

import dev.vatn.api.VFilterChain;
import dev.vatn.api.VHttpFilter;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP filter that validates Bearer tokens and stores the resolved {@link AuthContext}
 * as a request attribute under the key {@code "vatn.auth"}.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>No {@code Authorization} header → proceed (attribute absent; handler decides)</li>
 *   <li>Valid Bearer token → set {@code "vatn.auth"} attribute and proceed</li>
 *   <li>Malformed or expired token → respond 401 immediately</li>
 * </ul>
 *
 * <p>Route handlers can read the context via:
 * <pre>{@code
 * req.getAttribute("vatn.auth", AuthContext.class)
 *    .ifPresent(ctx -> ...);
 * }</pre>
 */
public class AuthFilter implements VHttpFilter {

    public static final String AUTH_ATTRIBUTE = "vatn.auth";

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public int order() {
        return VHttpFilter.AUTH;
    }

    @Override
    public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
        String authHeader = req.getHeader("Authorization");

        if (authHeader == null || authHeader.isBlank()) {
            chain.proceed(req, res);
            return;
        }

        if (!authHeader.startsWith("Bearer ")) {
            res.status(401).sendJson("{\"error\":\"Malformed Authorization header — expected Bearer token\"}");
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();

        try {
            AuthContext ctx = authService.authenticate(token);
            req.setAttribute(AUTH_ATTRIBUTE, ctx);
            log.debug("Authenticated request for subject '{}'", ctx.subject());
        } catch (AuthenticationException e) {
            log.debug("Token rejected: {}", e.getMessage());
            res.status(401).sendJson("{\"error\":\"Invalid or expired token\"}");
            return;
        }

        chain.proceed(req, res);
    }
}
