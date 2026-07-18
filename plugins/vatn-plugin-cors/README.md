# vatn-plugin-cors

Adds Cross-Origin Resource Sharing (CORS) headers to all HTTP responses.

## How it works

Implements `VHttpFilter` at order 150, which places it early in the filter chain. For `OPTIONS` preflight requests the filter returns `204 No Content` immediately with the configured `Access-Control-*` headers, short-circuiting further handler processing. For all other requests it appends the same headers before the response is sent. No service interface is registered.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-cors</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
// Allow all origins (development / public APIs)
VNodeRunner.create()
    .addPlugin(new CorsPlugin(CorsConfig.permissive()))
    .run();

// Locked-down production config
CorsConfig config = CorsConfig.of(List.of("https://app.example.com", "https://admin.example.com"))
    .withAllowedMethods("GET", "POST", "PUT", "DELETE")
    .withAllowedHeaders("Content-Type", "Authorization")
    .withMaxAge(3600)
    .withAllowCredentials(true);

VNodeRunner.create()
    .addPlugin(new CorsPlugin(config))
    .run();
```

## API

This plugin registers no service interface. CORS headers are applied automatically to every response.

## Configuration

| Option             | Default (`permissive()`) | Meaning                                                       |
|--------------------|--------------------------|---------------------------------------------------------------|
| `allowedOrigins`   | `*`                      | Origins allowed to make cross-site requests                   |
| `allowedMethods`   | `*`                      | HTTP methods listed in `Access-Control-Allow-Methods`         |
| `allowedHeaders`   | `*`                      | Headers listed in `Access-Control-Allow-Headers`              |
| `maxAge`           | not set                  | `Access-Control-Max-Age` preflight cache duration in seconds  |
| `allowCredentials` | `false`                  | Whether `Access-Control-Allow-Credentials: true` is added     |

## Notes

- `CorsConfig.permissive()` sets `Access-Control-Allow-Origin: *`; combining this with `allowCredentials(true)` is rejected by browsers — use an explicit origin list instead.
- The filter runs at order 150; other filters at lower order numbers execute before CORS headers are added.
- `OPTIONS` requests are terminated by the filter — no route handler is invoked for preflight.
- `withAllowedMethods` and `withAllowedHeaders` accept varargs or a `List<String>`.
