package dev.vatn.plugins.cors;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CORS plugin for VATN. Registers a {@link CorsFilter} (order=150) that adds
 * Cross-Origin headers to every response and handles OPTIONS preflight instantly.
 *
 * <pre>{@code
 * // Permissive — open to all origins (development / public APIs)
 * VNodeRunner.create(8080)
 *     .addPlugin(new CorsPlugin())
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 *
 * // Production — explicit origin allowlist
 * VNodeRunner.create(8080)
 *     .addPlugin(new CorsPlugin(
 *         CorsConfig.of("https://app.example.com")
 *             .withCredentials(true)))
 *     .start();
 * }</pre>
 */
public class CorsPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(CorsPlugin.class);

    private final CorsConfig config;

    public CorsPlugin() {
        this(CorsConfig.permissive());
    }

    public CorsPlugin(CorsConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.cors"; }
    @Override public String getName()    { return "VATN CORS Plugin"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.registerFilter(new CorsFilter(config));
        log.info("CORS filter registered — origins: {}", config.getAllowedOrigins());
    }

    @Override
    public void onShutdown() {}
}
