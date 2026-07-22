# vatn-plugin-redis
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Provides Redis key-value operations and pub/sub messaging via Jedis.

## How it works

Creates a Jedis client on plugin initialisation using the supplied host, port, and optional credentials. Command operations (get, set, delete, etc.) use a single synchronous Jedis connection. Pub/sub subscriptions run on a dedicated virtual thread using a `JedisPubSub` instance, keeping the subscriber loop off the main thread pool. The client is closed on node shutdown.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-redis</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
RedisConfig config = RedisConfig.of("localhost", 6379)
    .withPassword("secret")
    .withDatabase(0)
    .withTimeout(2000);

VNodeRunner.create()
    .addPlugin(new RedisPlugin(config))
    .run();
```

## API

```java
public interface RedisService {
    String  get(String key);
    void    set(String key, String value);
    void    set(String key, String value, int ttlSeconds);
    void    delete(String key);
    boolean exists(String key);
    void    expire(String key, int seconds);
    void    publish(String channel, String message);
    void    subscribe(String channel, Consumer<String> handler);
}
```

```java
RedisService redis = ctx.service(RedisService.class);

// Cache with TTL
redis.set("session:abc123", userId, 3600);
String uid = redis.get("session:abc123");

// Existence check
if (!redis.exists("rate:user:42")) {
    redis.set("rate:user:42", "1", 60);
}

// Pub/sub
redis.subscribe("events", msg -> System.out.println("Received: " + msg));
redis.publish("events", "node-started");
```

## Configuration

| Option      | Default    | Meaning                                           |
|-------------|------------|---------------------------------------------------|
| `host`      | —          | Redis server hostname                             |
| `port`      | —          | Redis server port (typically 6379)                |
| `password`  | not set    | AUTH password (omit for unauthenticated servers)  |
| `database`  | `0`        | Logical Redis database index (0–15)               |
| `timeout`   | Jedis default | Socket connect/read timeout in milliseconds    |

## Notes

- A single command connection is shared across all callers; for concurrent high-throughput workloads consider wrapping the plugin with a `JedisPool` instead.
- `subscribe` is non-blocking — it starts the `JedisPubSub` loop on a virtual thread and returns immediately; the `Consumer<String>` is called on that thread.
- `publish` uses the command connection and is safe to call from any thread.
- The separate subscription connection means `subscribe` and command operations do not block each other.
