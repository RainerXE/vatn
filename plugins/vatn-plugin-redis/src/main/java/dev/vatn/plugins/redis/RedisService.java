package dev.vatn.plugins.redis;

import dev.vatn.api.VService;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis client service registered by {@link RedisPlugin}.
 *
 * <pre>{@code
 * RedisService redis = ctx.getService(RedisService.class).orElseThrow();
 *
 * redis.set("session:abc123", userId, 3600);          // with TTL
 * String uid = redis.get("session:abc123");
 * redis.incr("metrics:requests:today");
 * redis.publish("notifications", "{\"type\":\"alert\"}");
 * }</pre>
 */
public interface RedisService extends VService {

    // ── strings ────────────────────────────────────────────────────────────
    String get(String key);
    void   set(String key, String value);
    void   set(String key, String value, int ttlSeconds);
    void   del(String key);
    boolean exists(String key);
    long   incr(String key);
    long   incrBy(String key, long delta);
    boolean expire(String key, int ttlSeconds);
    long   ttl(String key);

    // ── hashes ─────────────────────────────────────────────────────────────
    String hget(String key, String field);
    void   hset(String key, String field, String value);
    Map<String, String> hgetAll(String key);
    void   hdel(String key, String... fields);

    // ── lists ──────────────────────────────────────────────────────────────
    void   lpush(String key, String... values);
    void   rpush(String key, String... values);
    String lpop(String key);
    String rpop(String key);
    List<String> lrange(String key, long start, long stop);
    long   llen(String key);

    // ── sets ───────────────────────────────────────────────────────────────
    void   sadd(String key, String... members);
    Set<String> smembers(String key);
    boolean sismember(String key, String member);

    // ── pub/sub ────────────────────────────────────────────────────────────
    /** Publishes a message to a channel. Returns number of subscribers that received it. */
    long publish(String channel, String message);
}
