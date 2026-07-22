# vatn-plugin-admin
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Serves a built-in HTML dashboard for inspecting a live VATN node.

## How it works

Registers a single `GET` route (default `/vatn/admin`) that returns a minimal HTML page showing the node ID, all loaded plugins, all registered HTTP routes, and the result of every registered health check. Access is gated by a bearer token read from the `VATN_ADMIN_TOKEN` environment variable or supplied directly in config. No service interface is registered — this plugin is purely route-based.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-admin</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
// Token from environment (VATN_ADMIN_TOKEN) — recommended
VNodeRunner.create()
    .addPlugin(new AdminPlugin(AdminConfig.defaults()))
    .run();

// Or supply the token explicitly
VNodeRunner.create()
    .addPlugin(new AdminPlugin(AdminConfig.of("my-secret-token")))
    .run();
```

## API

This plugin registers no service interface. Access the dashboard directly in a browser or via `curl`:

```bash
curl -H "Authorization: Bearer my-secret-token" http://localhost:8080/vatn/admin
```

The response is an HTML page containing:
- Node ID
- List of loaded plugins
- Table of registered HTTP routes
- Health check results for all registered services

## Configuration

| Option       | Default        | Env var            | Meaning                                 |
|--------------|----------------|--------------------|-----------------------------------------|
| `adminToken` | —              | `VATN_ADMIN_TOKEN` | Bearer token required to access the dashboard |
| `path`       | `/vatn/admin`  | —                  | URL path for the dashboard endpoint     |

## Notes

- `AdminConfig.defaults()` reads the token exclusively from `VATN_ADMIN_TOKEN`; the node will fail to start if the variable is unset.
- Requests without a valid `Authorization: Bearer <token>` header receive a `401` response.
- The dashboard is read-only — it exposes no management actions.
- Health check results reflect the state at request time; there is no caching or push-based refresh.
