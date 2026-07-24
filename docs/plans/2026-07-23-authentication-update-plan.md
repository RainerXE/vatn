# Authentication Update — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Upgrade the authentication system from the current dev-focused setup to a production-capable, federation-ready architecture with master token exchange, refresh token rotation, and revocation support.

**Architecture:** OAuth 2.0 Token Exchange (RFC 8693) as the core pattern — a Security Token Service (STS) that accepts master tokens or credentials and issues short-lived, audience-scoped JWTs. Refresh token rotation with replay detection. Optional Redis-backed JTI blacklist for instant revocation. JWK Set publishing for cross-node verification.

**Tech Stack:** Java 25, vatn-api, JJWT 0.12.x, optional: Jedis/Lettuce for Redis

---

## Phase 1 — Immediate Fixes & Hardening

### Task 1: Fix Distrobox start using container name instead of hash

**Files:**
- Edit: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/DistroboxManager.java`

**Problem:** `DistroboxManager.startContainer()` and `stopContainer()` use `c.id()` (container hash from `distrobox list` column 1), but `distrobox start` expects the **container name** (column 2). Stop works because `distrobox stop --yes` delegates to `podman stop` which accepts hashes.

**Step 1: Pass name alongside id where available**

Option A — change `startContainer`/`stopContainer` to take name:
- Change the `ContainerManager` interface to `startContainer(String id, String name)` with `startContainer(String id)` as default delegating to `startContainer(id, id)` for backward compat
- In `ContainersPlugin.java` button rendering, pass both id and name
- In `DistroboxManager`, use name for start, keep using id for stop (it works)

Option B — simpler: use name everywhere in DistroboxManager:
- `listContainers()` returns `VContainer` with `id` = hash, `name` = name
- Change `startContainer` to use the name field from `distrobox list` instead of the hash
- Requires changing how the button passes the identifier

### Task 2: Reduce access token TTL to 15 minutes

**Files:**
- Edit: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/AuthConfig.java`

**Change:** Default `accessTokenTtl` from `3600` (1h) to `900` (15 min). This limits the window for a stolen access token.

### Task 3: Add `jti` claim to all issued tokens

**Files:**
- Edit: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/JwtAuthService.java`

**Change:** Generate a random UUID for each access token and refresh token, include it as the `jti` claim. This enables future revocation by JTI.

### Task 4: Add `iat` and `aud` claims

**Files:**
- Edit: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/JwtAuthService.java`

**Change:** Include `iat` (issued-at) timestamp and `aud` (audience) claim in all tokens. Default audience: node ID or configurable.

---

## Phase 2 — Refresh Token Rotation & Revocation

### Task 5: Implement refresh token rotation with replay detection

**Files:**
- Edit: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/JwtAuthService.java`
- Edit: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/AuthService.java`
- Edit: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/AuthPlugin.java`
- Create: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/RefreshTokenStore.java`

**Step 1: Create RefreshTokenStore interface**

```java
public interface RefreshTokenStore {
    /** Store refresh token hash with family ID and expiry. Returns true if stored (first time). */
    boolean store(String familyId, String tokenHash, Instant expiresAt);
    /** Rotate: atomically validate old hash and store new. Returns false if replay detected. */
    boolean rotate(String familyId, String oldHash, String newHash, Instant expiresAt);
    /** Revoke entire token family. */
    void revokeFamily(String familyId);
    /** Revoke all families for a user. */
    void revokeAllForUser(String userId);
}
```

**Step 2: InMemoryRefreshTokenStore (default, no Redis dep)**

ConcurrentHashMap-based, with periodic cleanup of expired entries. Production can swap to Redis-backed.

**Step 3: Modify JwtAuthService**

- On `login()`: generate `familyId` (UUID), store refresh token hash
- On `refresh()`: validate old refresh token, call `store.rotate()`, issue new pair
- On replay detection (rotate returns false): revoke entire family, return 401 with `token_family_revoked` error
- Store refresh tokens as opaque random strings (not JWTs)

### Task 6: Add token introspection endpoint (RFC 7662)

**Files:**
- Create: New route in `AuthPlugin.java`

**Step 1:** Add `POST /auth/introspect` endpoint:
- Accepts `token` parameter
- Returns `{ "active": true/false, "sub": "...", "exp": ..., "jti": "..." }`
- Validates signature, checks expiry, checks JTI blacklist
- Used by other nodes/services to back-check token validity

### Task 7: Add token revocation endpoint (RFC 7009)

**Files:**
- Create: New route in `AuthPlugin.java`
- Edit: `JwtAuthService.java`

**Step 1:** Add `POST /auth/revoke` endpoint:
- Accepts `token` parameter
- Extracts `jti`, adds to blacklist with TTL = remaining token lifetime
- If refresh token: revoke entire family via `RefreshTokenStore.revokeFamily()`

### Task 8: Optional Redis-backed JTI blacklist

**Files:**
- Create: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/RedisTokenBlacklist.java`

**Step 1:** If Redis is available, maintain a SET of revoked JTIs with per-key TTL matching remaining token lifetime. Check on every authenticated request.

**Integration:** In `AuthFilter`, after JWT signature verification, check blacklist. If revoked → 401.

---

## Phase 3 — Master Token Exchange (RFC 8693)

### Task 9: Implement `POST /auth/token-exchange`

**Files:**
- Edit: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/AuthPlugin.java`
- Create: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/TokenExchangeService.java`

**Step 1: Create TokenExchangeService**

Handles the grant type `urn:ietf:params:oauth:grant-type:token-exchange`.

Accepts:
- `subject_token` — the master token (`VATN_ADMIN_TOKEN` or a long-lived JWT)
- `subject_token_type` — e.g. `urn:ietf:params:oauth:token-type:access_token`
- `scope` — requested scopes (e.g. `containers:read containers:write`)
- `audience` — target service identifier

Returns a scoped, short-lived access token + refresh token pair.

**Step 2: Master token validation**

- If `VATN_ADMIN_TOKEN` matches → allow, embed `sub="admin"`, role claims
- If valid JWT with appropriate scopes → allow, downstream scope
- Otherwise → 403

**Step 3: Scope enforcement**

- Downscope: issued token's scope is intersection of requested scope and master token's allowed scope
- Master token (`VATN_ADMIN_TOKEN`) gets full scope: `*:*`
- Future: per-service scope configuration

### Task 10: Publish JWK Set endpoint

**Files:**
- Create: `GET /.well-known/jwks.json` route in `AuthPlugin.java`

**Step 1:** Generate an asymmetric key pair (ES256) for signing.
**Step 2:** Publish public key as JWK Set at a standard endpoint.
**Step 3:** Other nodes/services fetch and cache this JWK Set to verify tokens without calling back to the auth server.

---

## Phase 4 — Federation & Cross-Node Trust

### Task 11: Trust configuration schema

**Files:**
- Create: `plugins/vatn-plugin-auth/src/main/java/dev/vatn/plugins/auth/TrustedIssuer.java`

```java
public record TrustedIssuer(
    String issuerId,           // e.g. "node-a.vatn.network"
    String jwksUrl,            // e.g. "https://node-a.vatn.network/.well-known/jwks.json"
    List<String> allowedScopes, // e.g. ["containers:read"]
    long cacheTtlSec           // JWK set cache duration
) {}
```

**Step 1:** Load trusted issuers from config (JSON file or env).
**Step 2:** On token validation, check `iss` claim against trusted issuers.
**Step 3:** Fetch and cache JWK Sets, with background refresh.
**Step 4:** Validate signature using the issuer's public key.

### Task 12: Cross-node introspection for high-risk operations

**Step 1:** For sensitive operations (admin actions, token creation), call the issuer's introspection endpoint to confirm token is still valid (not yet revoked).
**Step 2:** Cache introspection results for a short period (e.g. 30s) to avoid excessive network calls.

---

## Migration Path

1. **Phase 1** — Drop-in: no breaking changes, can be shipped immediately
2. **Phase 2** — Adds state: existing tokens continue working until expiry; new tokens get rotation
3. **Phase 3** — New capability: `VATN_ADMIN_TOKEN` still works as before; token exchange is an additional path
4. **Phase 4** — Configuration-only: no code changes to existing services

### Backward Compatibility

- `VATN_ADMIN_TOKEN` continues working in all phases (it's checked in `AdminPlugin.authorized()` as fallback)
- Existing JWT tokens remain valid until their natural expiry
- Refresh token rotation only applies to newly issued tokens
- Old clients without rotation still work (they just get non-rotating refresh tokens until they use the new login endpoint)

---

## Files Summary

| Phase | File | Action |
|-------|------|--------|
| 1 | `DistroboxManager.java` | Fix: use name instead of hash for start |
| 1 | `AuthConfig.java` | Edit: accessTokenTtl 3600→900 |
| 1 | `JwtAuthService.java` | Edit: add jti, iat, aud claims |
| 2 | `RefreshTokenStore.java` | Create: interface + impl |
| 2 | `JwtAuthService.java` | Edit: rotation logic |
| 2 | `AuthService.java` | Edit: rotate() signature |
| 2 | `AuthPlugin.java` | Edit: /introspect, /revoke routes |
| 2 | `RedisTokenBlacklist.java` | Create: optional Redis-backed |
| 3 | `TokenExchangeService.java` | Create: RFC 8693 handler |
| 3 | `AuthPlugin.java` | Edit: /token-exchange route |
| 3 | `AuthConfig.java` | Edit: key pair generation, JWKS |
| 4 | `TrustedIssuer.java` | Create: trust config record |
| 4 | `AuthPlugin.java` | Edit: multi-issuer validation |

---

## Open Questions

1. **Redis dependency**: Should Phase 2 require Redis, or keep in-memory as default with Redis as upgrade path?
2. **JWK rotation**: How to handle key rotation without invalidating all existing tokens?
3. **Federation discovery**: How do nodes discover each other's trust configuration — static config file, or dynamic discovery?
4. **UI for token management**: Should the admin dashboard show active sessions, allow revocation?
