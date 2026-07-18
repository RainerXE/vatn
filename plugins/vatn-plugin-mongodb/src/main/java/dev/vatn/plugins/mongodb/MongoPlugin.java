package dev.vatn.plugins.mongodb;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

/**
 * MongoDB plugin — registers a {@link MongoService} backed by the official sync driver.
 *
 * <pre>{@code
 * node.use(new MongoPlugin(MongoConfig.localhost("myapp")));
 * // or:
 * node.use(new MongoPlugin(MongoConfig.of("mongodb+srv://user:pass@cluster.mongodb.net", "myapp")));
 * }</pre>
 */
public class MongoPlugin implements VNodePlugin {

    private final MongoConfig config;
    private MongoServiceImpl service;

    public MongoPlugin(MongoConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.mongodb"; }
    @Override public String getName()    { return "VATN MongoDB Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        service = new MongoServiceImpl(config);
        ctx.registerService(MongoService.class, service);
        ctx.registerHealthCheck("mongodb", () -> {
            service.database().runCommand(new org.bson.Document("ping", 1));
            return true;
        });
    }

    @Override
    public void onShutdown() {
        if (service != null) service.close();
    }
}
