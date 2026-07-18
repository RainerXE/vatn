package dev.vatn.plugins.redis;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

/**
 * Redis plugin — registers a {@link RedisService} backed by Jedis.
 *
 * <pre>{@code
 * node.use(new RedisPlugin(RedisConfig.localhost()));
 * // or with auth:
 * node.use(new RedisPlugin(RedisConfig.of("redis.example.com", 6379)
 *         .withPassword("secret")
 *         .withDatabase(1)
 *         .withPoolSize(20)));
 * }</pre>
 */
public class RedisPlugin implements VNodePlugin {

    private final RedisConfig config;
    private JedisRedisService service;

    public RedisPlugin(RedisConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.redis"; }
    @Override public String getName()    { return "VATN Redis Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        service = new JedisRedisService(config);
        ctx.registerService(RedisService.class, service);
        ctx.registerHealthCheck("redis", () -> { service.exists("__vatn_hc"); return true; });
    }

    @Override
    public void onShutdown() {
        if (service != null) service.close();
    }
}
