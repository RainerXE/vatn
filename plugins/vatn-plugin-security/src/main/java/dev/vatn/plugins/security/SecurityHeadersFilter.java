package dev.vatn.plugins.security;

import dev.vatn.api.VFilterChain;
import dev.vatn.api.VHttpFilter;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;

/**
 * HTTP filter that injects security headers into every plugin route response.
 *
 * <p>Headers are written before {@link VFilterChain#proceed} is called so they
 * are always present even if a downstream filter or handler throws.
 */
public class SecurityHeadersFilter implements VHttpFilter {

    private final SecurityConfig config;

    public SecurityHeadersFilter(SecurityConfig config) {
        this.config = config;
    }

    @Override
    public int order() {
        return VHttpFilter.SECURITY;
    }

    @Override
    public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
        applyHeaders(res);
        chain.proceed(req, res);
    }

    private void applyHeaders(VHttpResponse res) {
        if (config.getFrameOptions() != null) {
            res.setHeader("X-Frame-Options", config.getFrameOptions());
        }
        if (config.isContentTypeOptions()) {
            res.setHeader("X-Content-Type-Options", "nosniff");
        }
        if (config.isXssProtection()) {
            res.setHeader("X-XSS-Protection", "1; mode=block");
        }
        if (config.getHsts() != null) {
            res.setHeader("Strict-Transport-Security", config.getHsts());
        }
        if (config.getCsp() != null) {
            res.setHeader("Content-Security-Policy", config.getCsp());
        }
        if (config.getReferrerPolicy() != null) {
            res.setHeader("Referrer-Policy", config.getReferrerPolicy());
        }
        if (config.getPermissionsPolicy() != null) {
            res.setHeader("Permissions-Policy", config.getPermissionsPolicy());
        }
    }
}
