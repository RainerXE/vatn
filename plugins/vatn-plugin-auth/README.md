# vatn-plugin-auth
![Stability](https://img.shields.io/badge/stability-stable-green)

JWT authentication for VATN nodes in three lines of setup. Adds login, token refresh, and bearer token validation — all over standard HTTP.

## What It Does

- Issues signed HMAC-SHA256 JWTs (access + refresh token pair) on successful login
- Validates refresh tokens and re-issues a new token pair
- Exposes a `/auth/me` endpoint that decodes the caller's identity from a bearer token
- Registers an `AuthService` in the VATN service registry so other plugins can authenticate requests without HTTP roundtrips

## Quick Start

```java
import dev.vatn.plugins.auth.*;

// 1. Define how credentials are validated
CredentialsValidator validator = (username, password) -> {
    if ("alice".equals(username) && "s3cr3t".equals(password)) {
        return Map.of("role", "admin");
    }
    throw new InvalidCredentialsException("Bad credentials");
};

// 2. Build config (secret must be >= 32 chars)
AuthConfig config = AuthConfig.of(
    "my-super-secret-signing-key-32ch",
    validator
);

// 3. Add the plugin to your node runner
VNodeRunner runner = VNodeRunner.create();
runner.addPlugin(new AuthPlugin(config));
runner.start();
```

That's it. The endpoints are live at `http://localhost:8080/auth/*`.

### Using AuthService from Another Plugin

```java
public class OrderPlugin implements VNodePlugin {

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.register("/orders", new OrderService(ctx));
    }

    private static class OrderService implements VHttpService {
        private final VNodeContext ctx;

        OrderService(VNodeContext ctx) { this.ctx = ctx; }

        @Override
        public void routing(VHttpRoutes routes) {
            routes.get("/", (req, res) -> {
                AuthService auth = ctx.getService(AuthService.class)
                    .orElseThrow(() -> new IllegalStateException("AuthPlugin not loaded"));

                String header = req.getHeader("Authorization");
                if (header == null || !header.startsWith("Bearer ")) {
                    res.status(401).send("Unauthorized");
                    return;
                }

                AuthContext caller = auth.authenticate(header.substring(7));
                if (!caller.hasRole("admin")) {
                    res.status(403).send("Forbidden");
                    return;
                }

                res.sendJson("[{\"id\":1,\"item\":\"Widget\"}]");
            });
        }
    }
}
```

## Configuration Reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `secret` | `String` | — | HMAC-SHA256 signing key. **Minimum 32 characters.** |
| `accessTokenTtlSeconds` | `long` | `3600` | Lifetime of an access token (1 hour). |
| `refreshTokenTtlSeconds` | `long` | `604800` | Lifetime of a refresh token (7 days). |
| `issuer` | `String` | `"vatn"` | JWT `iss` claim. |
| `validator` | `CredentialsValidator` | — | Strategy that checks credentials and returns custom claims. |

Use `AuthConfig.of(secret, validator)` to get the defaults. Use the full constructor for custom TTLs or issuer:

```java
AuthConfig config = new AuthConfig(
    secret,
    900,      // 15-minute access tokens
    86400,    // 1-day refresh tokens
    "my-app",
    validator
);
```

## HTTP Endpoints

All endpoints are mounted under `/auth`.

### `POST /auth/login`

Exchange credentials for a token pair.

**Request body:**
```json
{"username": "alice", "password": "s3cr3t"}
```

**Response `200 OK`:**
```json
{
  "accessToken":  "eyJ...",
  "refreshToken": "eyJ...",
  "expiresIn":    3600
}
```

**Response `401 Unauthorized`:**
```json
{"error": "Invalid credentials"}
```

---

### `POST /auth/refresh`

Exchange a valid refresh token for a new token pair.

**Request body:**
```json
{"refreshToken": "eyJ..."}
```

**Response `200 OK`:** same shape as login.

**Response `401 Unauthorized`:**
```json
{"error": "Invalid or expired refresh token"}
```

---

### `GET /auth/me`

Decode the caller's identity from a bearer token.

**Request header:**
```
Authorization: Bearer eyJ...
```

**Response `200 OK`:**
```json
{
  "subject":   "alice",
  "claims":    {"role": "admin"},
  "expiresAt": "2026-05-28T10:00:00Z"
}
```

**Response `401 Unauthorized`:**
```json
{"error": "Invalid or expired token"}
```

## Customising Credential Validation

`CredentialsValidator` is a `@FunctionalInterface` — pass any lambda or method reference.

**Hardcoded map (good for tests and demos):**
```java
Map<String, String> users = Map.of(
    "alice", "s3cr3t",
    "bob",   "p@ssw0rd"
);

CredentialsValidator validator = (username, password) -> {
    if (password.equals(users.get(username))) {
        return Map.of("role", "user");
    }
    throw new InvalidCredentialsException("Bad credentials");
};
```

**Database-backed (production pattern):**
```java
CredentialsValidator validator = (username, password) -> {
    UserRecord user = db.findUser(username)
        .orElseThrow(() -> new InvalidCredentialsException("Unknown user"));

    if (!BCrypt.checkpw(password, user.passwordHash())) {
        throw new InvalidCredentialsException("Wrong password");
    }

    return Map.of(
        "role",   user.role(),
        "userId", user.id()
    );
};
```

## Using AuthService in Your Own Plugin

Once `AuthPlugin` is loaded, any plugin can retrieve `AuthService` from the context:

```java
AuthService auth = ctx.getService(AuthService.class)
    .orElseThrow(() -> new IllegalStateException("AuthPlugin must be loaded before this plugin"));

// Throws AuthenticationException if invalid
AuthContext caller = auth.authenticate(rawToken);

// Returns Optional.empty() instead of throwing
Optional<AuthContext> maybe = auth.tryAuthenticate(rawToken);
```
