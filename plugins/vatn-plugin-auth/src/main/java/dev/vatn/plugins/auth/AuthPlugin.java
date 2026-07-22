package dev.vatn.plugins.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VHttpFilter;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drop-in JWT authentication plugin for the VATN runtime.
 *
 * <p>Registers an {@link AuthService} in the node context and exposes three HTTP
 * endpoints under {@code /auth}:
 * <ul>
 *   <li>{@code POST /auth/login}   — exchange credentials for a {@link TokenPair}</li>
 *   <li>{@code POST /auth/refresh} — exchange a refresh token for a new {@link TokenPair}</li>
 *   <li>{@code GET  /auth/me}      — validate an access token and return the caller's {@link AuthContext}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * AuthConfig config = AuthConfig.of(
 *     "your-32-plus-char-secret-key-here!!",
 *     (username, password) -> {
 *         if ("alice".equals(username) && "secret".equals(password)) {
 *             return Map.of("role", "admin");
 *         }
 *         throw new InvalidCredentialsException("Invalid credentials");
 *     }
 * );
 * runner.addPlugin(new AuthPlugin(config));
 * }</pre>
 */
public class AuthPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(AuthPlugin.class);

    private static final String PLUGIN_ID = "dev.vatn.plugins.auth";
    private static final String PLUGIN_NAME = "VATN Auth Plugin";
    private static final String PLUGIN_VERSION = "1.0-alpha.14";

    private final AuthConfig config;
    private final ObjectMapper mapper;

    private AuthService authService;

    public AuthPlugin(AuthConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // VNodePlugin
    // -------------------------------------------------------------------------

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return PLUGIN_NAME;
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Initializing {}", PLUGIN_NAME);

        authService = new JwtAuthService(config);
        ctx.registerService(AuthService.class, authService);
        log.debug("AuthService registered in VNodeContext");

        ctx.registerFilter(new AuthFilter(authService));
        log.debug("AuthFilter registered in VNodeContext");

        ctx.register("/auth", new AuthHttpService());
        log.info("{} initialized — routes registered at /auth, filter active at order {}", PLUGIN_NAME, VHttpFilter.AUTH);
    }

    @Override
    public void onShutdown() {
        log.info("{} shutting down", PLUGIN_NAME);
    }

    // -------------------------------------------------------------------------
    // HTTP service
    // -------------------------------------------------------------------------

    private class AuthHttpService implements VHttpService {

        @Override
        public void routing(VHttpRoutes routes) {
            routes.post("/login", AuthPlugin.this::handleLogin);
            routes.post("/refresh", AuthPlugin.this::handleRefresh);
            routes.get("/me", AuthPlugin.this::handleMe);
        }
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    private void handleLogin(VHttpRequest req, VHttpResponse res) throws Exception {
        Map<String, String> body;
        try {
            body = parseBody(req.getBody());
        } catch (BadRequestException e) {
            res.status(400).sendJson(errorJson("Malformed request body"));
            return;
        }
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            res.status(400).sendJson(errorJson("Fields 'username' and 'password' are required"));
            return;
        }

        try {
            TokenPair pair = authService.login(username, password);
            res.sendJson(mapper.writeValueAsString(pair));
        } catch (InvalidCredentialsException e) {
            log.debug("Login failed for '{}': {}", username, e.getMessage());
            res.status(401).sendJson(errorJson("Invalid credentials"));
        } catch (RuntimeException e) {
            // Validators are arbitrary user code; a validator that throws anything other than
            // InvalidCredentialsException must never leak a 500 oracle to the client.
            log.warn("Credential validator failed unexpectedly for '{}': {}", username, e.toString());
            res.status(401).sendJson(errorJson("Invalid credentials"));
        }
    }

    private void handleRefresh(VHttpRequest req, VHttpResponse res) throws Exception {
        Map<String, String> body;
        try {
            body = parseBody(req.getBody());
        } catch (BadRequestException e) {
            res.status(400).sendJson(errorJson("Malformed request body"));
            return;
        }
        String refreshToken = body.get("refreshToken");

        if (refreshToken == null || refreshToken.isBlank()) {
            res.status(400).sendJson(errorJson("Field 'refreshToken' is required"));
            return;
        }

        try {
            TokenPair pair = authService.refresh(refreshToken);
            res.sendJson(mapper.writeValueAsString(pair));
        } catch (AuthenticationException e) {
            log.debug("Token refresh failed: {}", e.getMessage());
            res.status(401).sendJson(errorJson("Invalid or expired refresh token"));
        }
    }

    private void handleMe(VHttpRequest req, VHttpResponse res) throws Exception {
        AuthContext ctx = req.getAttribute(AuthFilter.AUTH_ATTRIBUTE, AuthContext.class)
                .orElse(null);
        if (ctx == null) {
            res.status(401).sendJson(errorJson("Authorization required"));
            return;
        }
        res.sendJson(mapper.writeValueAsString(authContextToMap(ctx)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, String> parseBody(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(body, new TypeReference<Map<String, String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("Malformed request body", e);
        }
    }

    /**
     * Converts an {@link AuthContext} to a plain {@link Map} so Jackson can serialise it
     * without requiring the {@code jackson-datatype-jsr310} module. {@link java.time.Instant}
     * is rendered as an ISO-8601 string via {@code toString()}.
     */
    private Map<String, Object> authContextToMap(AuthContext ctx) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("subject", ctx.subject());
        map.put("claims", ctx.claims());
        map.put("expiresAt", ctx.expiresAt().toString());
        return map;
    }

    private String errorJson(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"" + message + "\"}";
        }
    }
}
