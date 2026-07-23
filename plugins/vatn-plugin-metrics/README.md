# vatn-plugin-metrics
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Exposes a Prometheus-compatible metrics endpoint powered by Micrometer.

## How it works

Creates a Micrometer `PrometheusMeterRegistry` and optionally binds standard JVM binders (GC pauses, memory pools, thread counts, CPU usage) from the Micrometer JVM extras. The registry is exposed as a registered `MetricsService` so other plugins and application code can record custom metrics. A plain-text HTTP handler serves the Prometheus scrape format at the configured path (default `/metrics`).

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-metrics</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
// Default: /metrics path + JVM metrics enabled
VNodeRunner.create()
    .addPlugin(new MetricsPlugin(MetricsConfig.defaults()))
    .run();

// Custom path, no JVM metrics
VNodeRunner.create()
    .addPlugin(new MetricsPlugin(MetricsConfig.defaults()
        .withPath("/internal/metrics")
        .withoutJvmMetrics()))
    .run();
```

## API

```java
public interface MetricsService {
    MeterRegistry registry();
}
```

```java
MetricsService metrics = ctx.service(MetricsService.class);
MeterRegistry registry = metrics.registry();

// Counter
Counter requests = Counter.builder("http.requests")
    .tag("route", "/api/items")
    .register(registry);
requests.increment();

// Timer
Timer timer = Timer.builder("db.query.duration")
    .tag("table", "users")
    .register(registry);
timer.record(() -> performQuery());
```

## Configuration

| Option          | Default    | Meaning                                             |
|-----------------|------------|-----------------------------------------------------|
| `path`          | `/metrics` | URL path for the Prometheus scrape endpoint         |
| `jvmMetrics`    | `true`     | Whether to bind JVM GC, memory, thread, CPU meters  |

## Notes

- `MetricsConfig.defaults()` enables JVM metrics; call `.withoutJvmMetrics()` to disable them.
- The scrape endpoint returns `Content-Type: text/plain; version=0.0.4` as expected by Prometheus.
- Use `registry()` to share one `MeterRegistry` across all plugins rather than creating additional registries.
- Micrometer's `@Timed` and `@Counted` annotations are not wired automatically — use the registry API directly.
