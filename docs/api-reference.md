# VATN Plugin API Reference

Audience: plugin developers implementing `VNodePlugin` against the `vatn-api` SPI.

---

## 1. Built-in Runtime Endpoints

These endpoints are always registered by `VNodeRunner` regardless of plugins.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Aggregated health check. Returns `"UP"` (plain text) if no checks are registered, or `{"status":"UP","checks":{...}}` with per-check results. Returns 503 if any check returns false or throws. |
| GET | `/vatn/health` | Runtime health JSON: `{"status":"UP","nodeId":"...","uptimeMs":...}`. |
| GET | `/vatn/ready` | Returns `{"status":"READY"}` once all plugins are initialized and the server is bound. Returns 503 with `{"status":"STARTING"}` while starting. |
| GET | `/info` | Node metadata: `id`, `flavor` (JVM/AOT), `vatnVersion`, `plugins` count, `uptimeMs`. |
| GET | `/vatn/ui/stream` | SSE stream of internal UI bus events (event name: `ui-update`). Sends `ping` keepalive every 30 s. |
| PUT | `/stream/{id}` | Ingest a stream chunk by ID. Used by `VStream.createRemoteOutput(...)`. |

---

## 2. VNodeRunner

Bootstrap class in `dev.vatn.core`. Not part of the `vatn-api` SPI but used by application entry points.

**Factory methods**

```java
VNodeRunner.create(int port)
VNodeRunner.create(int port, Path pluginPath)
VNodeRunner.create(int port, Path pluginPath, Path identityPath)
```

`pluginPath` defaults to `./plugins`. `identityPath` defaults to `~/.vatn/identity.pem`.

**Instance methods**

| Method | Description |
|--------|-------------|
| `addPlugin(VNodePlugin)` | Register a plugin to be initialized on `start()`. |
| `registerWebSocket(String path, VWsListener)` | Register a WebSocket endpoint (transport-neutral). |
| `registerService(Class<T>, T)` | Pre-register a system service before `start()`. |
| `register(String path, VHttpService)` | Mount a `VHttpService` at a path prefix. |
| `start()` | Initialize all plugins, start Helidon, invoke `onReady()` on all plugins. |
| `stop()` | Stop the server and invoke `onShutdown()` on all plugins. |
| `getBoundPort()` | Returns the actual bound port (useful when `port=0`). |
| `getContext()` | Returns the `VNodeContext` after `start()`. |

---

## 3. VNodePlugin Lifecycle

Interface `dev.vatn.api.VNodePlugin`.

| Method | Default | When called |
|--------|---------|-------------|
| `String getId()` | required | Unique reverse-domain ID, e.g. `"dev.vatn.plugins.auth"`. |
| `String getName()` | required | Human-readable display name. |
| `String getVersion()` | required | Semver string, e.g. `"1.0.0"`. |
| `void onInitialize(VNodeContext ctx)` | no-op | During `VNodeRunner.start()` — register services, filters, and routes. |
| `void onReady()` | no-op | After server is fully bound and all plugins initialized. Safe to look up services registered by sibling plugins. Runs on a virtual thread. |
| `void onConfigReloaded()` | no-op | When configuration is refreshed. Re-read any cached config values here. |
| `void onShutdown()` | no-op | During `VNodeRunner.stop()` — close connections, flush state. |

---

## 4. VNodeContext

Interface `dev.vatn.api.VNodeContext`. Passed to `onInitialize`.

**Service registry**

```java
<T extends VService> void registerService(Class<T> type, T impl)
<T extends VService> Optional<T> getService(Class<T> type)
```

**HTTP routing**

```java
void register(String path, VHttpService service)
```

**HTTP filters**

```java
void registerFilter(VHttpFilter filter)                     // global — all plugin routes
void registerFilter(VHttpFilter filter, String pathPrefix)  // scoped to requests whose path starts with pathPrefix
```

**WebSocket**

```java
void registerWebSocket(String path, VWsListener listener)
```

**Health checks**

```java
void registerHealthCheck(String name, Supplier<Boolean> checker)
```

Registered checkers are aggregated into the built-in `GET /health` endpoint. A check that throws is treated as `DOWN`.

**Accessors**

| Method | Returns |
|--------|---------|
| `getNodeId()` | `String` |
| `getWorkspacePath()` | `Path` |
| `getConfiguration()` | `VConfiguration` |
| `getMessaging()` | `VMessaging` |
| `getStream()` | `VStream` |
| `getJson()` | `VJson` |
| `getClock()` | `VClockService` |
| `getGuard()` | `VGuardService` |
| `getSecrets()` | `VSecretService` |
| `getDiscovery()` | `VDiscovery` |
| `getPluginRegistry()` | `VPluginRegistry` |

---

## 5. VHttpRoutes

Interface `dev.vatn.api.VHttpRoutes`. Every method returns `VHttpRoutes` for fluent chaining. Passed to `VHttpService.routing(VHttpRoutes)`.

```java
routes.get(String path, VHttpHandler handler)
routes.post(String path, VHttpHandler handler)
routes.put(String path, VHttpHandler handler)
routes.delete(String path, VHttpHandler handler)
routes.patch(String path, VHttpHandler handler)
routes.options(String path, VHttpHandler handler)
routes.sse(String path, VSseHandler handler)        // Server-Sent Events (GET)
routes.register(String path, VHttpService service)  // sub-service mounting
```

**Handler signature**

```java
@FunctionalInterface
void handle(VHttpRequest req, VHttpResponse res) throws Exception
```

**Path parameters** use `{name}` syntax: `"/users/{id}"`.

---

## 6. VHttpRequest

Interface `dev.vatn.api.VHttpRequest extends VRequest`.

**Request metadata**

| Method | Returns | Notes |
|--------|---------|-------|
| `getMethod()` | `String` | `GET`, `POST`, etc. |
| `getPath()` | `String` | Raw request path. |
| `getSourceId()` | `String` | Remote address. |

**Path & query**

| Method | Returns | Notes |
|--------|---------|-------|
| `getPathParam(String name)` | `String` | Template param from `{name}` in the route pattern. |
| `getQueryParam(String name)` | `String` | `null` if absent. |
| `getQueryParam(String name, String defaultValue)` | `String` | Returns `defaultValue` if absent. |

**Headers**

| Method | Returns | Notes |
|--------|---------|-------|
| `getHeader(String name)` | `String` | Single header by name. |
| `getHeaders()` | `Map<String, String>` | All headers; names are lower-cased. Multi-value headers collapsed to first value. |
| `getContentType()` | `String` | Shortcut for `getHeader("Content-Type")`. |

**Body**

| Method | Returns | Notes |
|--------|---------|-------|
| `getBody()` | `String` | Body as string. |
| `getBodyBytes()` | `byte[]` | Raw bytes — binary uploads, signature verification. |
| `getFormParam(String name)` | `String` | Parses `application/x-www-form-urlencoded` body. Returns `null` if field absent or content-type doesn't match. |

**Cookies**

| Method | Returns | Notes |
|--------|---------|-------|
| `getCookie(String name)` | `String` | Parses `Cookie` header inline. Returns `null` if absent. |

**Per-request attributes** (filter → handler communication)

| Method | Returns | Notes |
|--------|---------|-------|
| `setAttribute(String key, Object value)` | `void` | Set in a filter, read downstream. |
| `getAttribute(String key, Class<T> type)` | `Optional<T>` | Empty if absent or wrong type. |

---

## 7. VHttpResponse

Interface `dev.vatn.api.VHttpResponse extends VResponse`.

**Status** — fluent, returns `VHttpResponse`

```java
res.status(404)
res.status(201).sendJson(body)
```

`setStatus(int code)` also available (returns `void`, inherited from `VResponse`).

**Headers** — fluent, returns `VHttpResponse`

```java
res.header("X-Custom", "value")
res.header("Content-Type", "application/activity+json").send(body)
```

`setHeader(String name, String value)` also available (returns `void`, inherited from `VResponse`).

**Body senders** — terminal, return `void`

| Method | Content-Type set | Notes |
|--------|-----------------|-------|
| `send(String content)` | — | Raw string body. |
| `send(byte[] content)` | — | Raw bytes. |
| `sendJson(String json)` | `application/json` | |
| `sendHtml(String html)` | `text/html;charset=UTF-8` | |
| `sendEmpty()` | — | Sets status 204, sends empty body. |
| `redirect(String url)` | — | 302 Found with `Location` header. |

**Cookies**

```java
res.setCookie("session", token)
res.setCookie("remember", "1", CookieOptions.secure().withMaxAge(2592000))
```

`CookieOptions` factories:

| Factory | Attributes |
|---------|-----------|
| `CookieOptions.defaults()` | `Path=/; HttpOnly; SameSite=Lax` |
| `CookieOptions.secure()` | `Path=/; HttpOnly; Secure; SameSite=Strict` |

Wither methods (all return a new immutable `CookieOptions`): `withPath`, `withDomain`, `withMaxAge`, `withHttpOnly`, `withSecure`, `withSameSite`.

> **Note:** The default `setCookie` implementation calls `setHeader`, which replaces any existing `Set-Cookie` header. For multiple cookies in one response, call `setCookie(name, value, options)` for each and use an `addHeader`-capable response implementation.

---

## 8. VHttpFilter

Interface `dev.vatn.api.VHttpFilter extends VService`.

```java
int order()                                                         // lower = runs first
void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception
```

Call `chain.proceed(req, res)` to pass control to the next filter or route handler. Omitting the call short-circuits the chain (e.g. return 401, handle CORS preflight).

**Order constants**

| Constant | Value | Recommended use |
|----------|-------|----------------|
| `VHttpFilter.TRACING` | 100 | Distributed tracing, request IDs |
| `VHttpFilter.CORS` | 150 | CORS headers and preflight |
| `VHttpFilter.SECURITY` | 200 | Security response headers |
| `VHttpFilter.AUTH` | 300 | Authentication / JWT validation |
| `VHttpFilter.RATE_LIMIT` | 400 | Rate limiting |
| `VHttpFilter.LOGGING` | 900 | Request / response logging |

Any integer is valid; the constants are anchors.

---

## 9. VConfiguration

Interface `dev.vatn.api.VConfiguration extends VService`. Reads environment variables first, then the config file loaded at startup.

| Method | Returns | Notes |
|--------|---------|-------|
| `get(String key)` | `Optional<String>` | |
| `get(String key, String defaultValue)` | `String` | |
| `getRequired(String key)` | `String` | Throws `IllegalStateException` if absent. |
| `getInt(String key, int defaultValue)` | `int` | Returns `defaultValue` if absent or non-numeric. |
| `getLong(String key, long defaultValue)` | `long` | Returns `defaultValue` if absent or non-numeric. |
| `getDouble(String key, double defaultValue)` | `double` | Returns `defaultValue` if absent or non-numeric. |
| `getBoolean(String key, boolean defaultValue)` | `boolean` | `"true"` (case-insensitive) = `true`. |
| `getList(String key)` | `List<String>` | Comma-split and trimmed. Empty list if absent. |
| `isAot()` | `boolean` | `true` in GraalVM native image. |
| `getDefaultTrustLevel()` | `String` | Node-level plugin trust policy. |

---

## 10. VWsListener / VWsSession

Register via `ctx.registerWebSocket(path, listener)`.

**VWsListener**

| Method | Default | Description |
|--------|---------|-------------|
| `void onOpen(VWsSession session)` | no-op | Connection established. |
| `void onMessage(VWsSession session, String text, boolean last)` | required | Incoming text frame. `last=true` on the final fragment. |
| `void onClose(VWsSession session, int statusCode, String reason)` | no-op | Connection closed normally. |
| `void onError(VWsSession session, Throwable t)` | no-op | Protocol or I/O error. |

**VWsSession**

| Method | Description |
|--------|-------------|
| `send(String text)` | Send a complete text message (`last=true`). |
| `send(String text, boolean last)` | Send a text frame, optionally marking it as the final fragment. |
| `close(int statusCode, String reason)` | Close the connection with a WebSocket status code. |

---

## 11. VSseHandler / VSseSink

For `routes.sse(path, handler)`. The sink is opened before the handler is invoked and closed automatically when the handler returns or the client disconnects.

**VSseHandler** (`@FunctionalInterface`)

```java
void handle(VHttpRequest req, VSseSink sink) throws Exception
```

**VSseSink** (`extends AutoCloseable`)

| Method | Description |
|--------|-------------|
| `emit(String name, String data, String id)` | Named event with data and optional replay ID. |
| `emit(String data)` | Unnamed data-only event (no `event:` or `id:` fields). |
| `close()` | Flush and terminate the event stream. |

---

## 12. Plugin Patterns

### Registering a service

```java
ctx.registerService(MyService.class, new MyServiceImpl(config));

// Consumers (e.g. in onReady() or another plugin):
MyService svc = ctx.getService(MyService.class).orElseThrow();
```

### Registering routes

```java
ctx.register("/api/users", routes -> routes
    .get("",          this::listUsers)
    .get("/{id}",     this::getUser)
    .post("",         this::createUser)
    .delete("/{id}",  this::deleteUser));
```

### Registering a global filter

```java
ctx.registerFilter(new RateLimitFilter(100));
```

### Registering a path-scoped filter

```java
ctx.registerFilter(new AuthFilter(authService), "/api");  // only /api/**
```

### Registering health checks

```java
ctx.registerHealthCheck("redis",    () -> redisService.exists("__hc"));
ctx.registerHealthCheck("postgres", () -> { try (var c = ds.getConnection()) { return c.isValid(1); } });
ctx.registerHealthCheck("mongodb",  () -> { mongoClient.database().runCommand(new Document("ping", 1)); return true; });
```

### Registering a WebSocket endpoint

```java
ctx.registerWebSocket("/ws/live", new VWsListener() {
    public void onMessage(VWsSession s, String text, boolean last) {
        s.send("echo: " + text);
    }
});
```

### Using VConfiguration

```java
String apiKey       = ctx.getConfiguration().getRequired("OPENAI_API_KEY");
int    redisPort    = ctx.getConfiguration().getInt("REDIS_PORT", 6379);
List<String> hosts  = ctx.getConfiguration().getList("ALLOWED_HOSTS");
```
