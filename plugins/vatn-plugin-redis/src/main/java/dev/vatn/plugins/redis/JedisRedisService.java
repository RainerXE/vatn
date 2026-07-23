package dev.vatn.plugins.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class JedisRedisService implements RedisService {

    private final JedisPool pool;

    JedisRedisService(RedisConfig config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getPoolSize());
        poolConfig.setTestOnBorrow(true);

        if (config.getPassword() != null) {
            this.pool = new JedisPool(poolConfig, config.getHost(), config.getPort(),
                    config.getTimeoutMs(), config.getPassword(), config.getDatabase());
        } else {
            this.pool = new JedisPool(poolConfig, config.getHost(), config.getPort(),
                    config.getTimeoutMs());
        }
    }

    void close() { pool.close(); }

    @Override public String get(String key) {
        try (Jedis j = pool.getResource()) { return j.get(key); }
    }

    @Override public void set(String key, String value) {
        try (Jedis j = pool.getResource()) { j.set(key, value); }
    }

    @Override public void set(String key, String value, int ttlSeconds) {
        try (Jedis j = pool.getResource()) { j.set(key, value, SetParams.setParams().ex(ttlSeconds)); }
    }

    @Override public void del(String key) {
        try (Jedis j = pool.getResource()) { j.del(key); }
    }

    @Override public boolean exists(String key) {
        try (Jedis j = pool.getResource()) { return j.exists(key); }
    }

    @Override public long incr(String key) {
        try (Jedis j = pool.getResource()) { return j.incr(key); }
    }

    @Override public long incrBy(String key, long delta) {
        try (Jedis j = pool.getResource()) { return j.incrBy(key, delta); }
    }

    @Override public boolean expire(String key, int ttlSeconds) {
        try (Jedis j = pool.getResource()) { return j.expire(key, (long) ttlSeconds) == 1L; }
    }

    @Override public long ttl(String key) {
        try (Jedis j = pool.getResource()) { return j.ttl(key); }
    }

    @Override public String hget(String key, String field) {
        try (Jedis j = pool.getResource()) { return j.hget(key, field); }
    }

    @Override public void hset(String key, String field, String value) {
        try (Jedis j = pool.getResource()) { j.hset(key, field, value); }
    }

    @Override public Map<String, String> hgetAll(String key) {
        try (Jedis j = pool.getResource()) { return j.hgetAll(key); }
    }

    @Override public void hdel(String key, String... fields) {
        try (Jedis j = pool.getResource()) { j.hdel(key, fields); }
    }

    @Override public void lpush(String key, String... values) {
        try (Jedis j = pool.getResource()) { j.lpush(key, values); }
    }

    @Override public void rpush(String key, String... values) {
        try (Jedis j = pool.getResource()) { j.rpush(key, values); }
    }

    @Override public String lpop(String key) {
        try (Jedis j = pool.getResource()) { return j.lpop(key); }
    }

    @Override public String rpop(String key) {
        try (Jedis j = pool.getResource()) { return j.rpop(key); }
    }

    @Override public List<String> lrange(String key, long start, long stop) {
        try (Jedis j = pool.getResource()) { return j.lrange(key, start, stop); }
    }

    @Override public long llen(String key) {
        try (Jedis j = pool.getResource()) { return j.llen(key); }
    }

    @Override public void sadd(String key, String... members) {
        try (Jedis j = pool.getResource()) { j.sadd(key, members); }
    }

    @Override public Set<String> smembers(String key) {
        try (Jedis j = pool.getResource()) { return j.smembers(key); }
    }

    @Override public boolean sismember(String key, String member) {
        try (Jedis j = pool.getResource()) { return j.sismember(key, member); }
    }

    @Override public long publish(String channel, String message) {
        try (Jedis j = pool.getResource()) { return j.publish(channel, message); }
    }
}
