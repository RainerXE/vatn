package dev.vatn.plugins.security;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

/**
 * Drop-in security headers plugin for VATN.
 *
 * <p>Registers a {@link SecurityHeadersFilter} that automatically injects HTTP
 * security headers (X-Frame-Options, X-Content-Type-Options, CSP, HSTS, …)
 * into every response served by plugin-registered routes.
 *
 * <p>Minimal setup — safe defaults out of the box:
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new SecurityPlugin())          // defaults
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 * }</pre>
 *
 * <p>Custom CSP and HSTS:
 * <pre>{@code
 * SecurityConfig config = SecurityConfig.defaults()
 *     .withCsp("default-src 'self'; script-src 'self' https://cdn.example.com")
 *     .withHsts("max-age=31536000; includeSubDomains");
 *
 * VNodeRunner.create(8080)
 *     .addPlugin(new SecurityPlugin(config))
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 * }</pre>
 */
public class SecurityPlugin implements VNodePlugin {

    private final SecurityConfig config;

    /** Creates a SecurityPlugin with safe default headers. */
    public SecurityPlugin() {
        this(SecurityConfig.defaults());
    }

    /** Creates a SecurityPlugin with the given configuration. */
    public SecurityPlugin(SecurityConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.security"; }
    @Override public String getName()    { return "VATN Security Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.registerFilter(new SecurityHeadersFilter(config));
    }

    @Override
    public void onShutdown() {}
}
