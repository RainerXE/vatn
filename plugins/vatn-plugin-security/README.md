# vatn-plugin-security

Drop-in HTTP security headers for VATN nodes. One line to add, zero configuration required.

---

## What it does

Registers a `VHttpFilter` that writes the following headers into **every response** served by plugin-registered routes — before the handler runs, so they are always present:

| Header | Default value |
|--------|--------------|
| `X-Frame-Options` | `SAMEORIGIN` |
| `X-Content-Type-Options` | `nosniff` |
| `X-XSS-Protection` | `1; mode=block` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Strict-Transport-Security` | *(disabled — set explicitly for HTTPS deployments)* |
| `Content-Security-Policy` | *(disabled — application-specific, set explicitly)* |
| `Permissions-Policy` | *(disabled — set explicitly if needed)* |

---

## Quick start

```java
VNodeRunner.create(8080)
    .addPlugin(new SecurityPlugin())   // safe defaults, no config needed
    .addPlugin(new MyAppPlugin())
    .start();
```

**With CSP and HSTS** (recommended for production):

```java
SecurityConfig config = SecurityConfig.defaults()
    .withCsp("default-src 'self'; script-src 'self' https://cdn.example.com")
    .withHsts("max-age=31536000; includeSubDomains");

VNodeRunner.create(8443)
    .addPlugin(new SecurityPlugin(config))
    .addPlugin(new MyAppPlugin())
    .start();
```

---

## Maven dependency

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-security</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

> **Prerequisite:** build the VATN repo locally first:
> ```bash
> cd ../vatn && mvn install -DskipTests
> ```

---

## Configuration reference

All options are set via `SecurityConfig` fluent builders:

```java
SecurityConfig config = SecurityConfig.defaults()
    // X-Frame-Options: DENY | SAMEORIGIN | ALLOW-FROM <uri>
    .withFrameOptions("DENY")

    // Content-Security-Policy — set to your app's policy
    .withCsp("default-src 'self'")

    // Strict-Transport-Security — HTTPS deployments only
    .withHsts("max-age=63072000; includeSubDomains; preload")

    // Referrer-Policy
    .withReferrerPolicy("no-referrer")

    // Permissions-Policy
    .withPermissionsPolicy("geolocation=(), microphone=()")

    // Disable individual headers if needed
    .withoutXssProtection()
    .withoutContentTypeOptions();
```

---

## How it works

`SecurityPlugin.onInitialize()` calls `ctx.registerFilter(new SecurityHeadersFilter(config))`.

VATN's `VHttpFilter` chain runs before every route handler. The filter sets headers on the response object and then calls `chain.proceed(req, res)`, allowing the actual handler to run. Filters execute in ascending `order()` — `SecurityPlugin` uses `VHttpFilter.SECURITY` (200), so it runs after tracing (100) and before auth (300).

You can stack multiple filters:

```java
VNodeRunner.create(8080)
    .addPlugin(new SecurityPlugin())   // order 200 — adds headers
    .addPlugin(new AuthPlugin(...))    // order 300 — validates Bearer token
    .addPlugin(new MyAppPlugin())
    .start();
```

---

## Combining with vatn-plugin-auth

Security headers and JWT authentication complement each other directly:

```java
SecurityConfig security = SecurityConfig.defaults()
    .withCsp("default-src 'self'");

AuthConfig auth = AuthConfig.of("my-secret-key-32-chars-minimum!!", (user, pass) ->
    userRepository.validate(user, pass));   // your credential logic

VNodeRunner.create(8080)
    .addPlugin(new SecurityPlugin(security))
    .addPlugin(new AuthPlugin(auth))
    .addPlugin(new MyAppPlugin())
    .start();
```

The security filter adds headers on the way out; the auth plugin validates the `Authorization` header on the way in.
