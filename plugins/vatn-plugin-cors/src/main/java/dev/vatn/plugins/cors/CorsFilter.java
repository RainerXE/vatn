package dev.vatn.plugins.cors;

import dev.vatn.api.VFilterChain;
import dev.vatn.api.VHttpFilter;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;

import java.util.List;

/**
 * VHttpFilter that adds CORS response headers and handles OPTIONS preflight requests.
 * Runs at order {@link VHttpFilter#CORS} (150) — after tracing (100) but before security headers (200).
 */
public class CorsFilter implements VHttpFilter {

    private final CorsConfig config;

    public CorsFilter(CorsConfig config) {
        this.config = config;
    }

    @Override
    public int order() { return VHttpFilter.CORS; }

    @Override
    public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
        String origin = req.getHeader("Origin");

        if (origin != null && !origin.isBlank()) {
            String allowed = resolveOrigin(origin);
            if (allowed != null) {
                res.setHeader("Access-Control-Allow-Origin", allowed);
                if (!"*".equals(allowed)) {
                    res.setHeader("Vary", "Origin");
                }
                if (config.isAllowCredentials()) {
                    res.setHeader("Access-Control-Allow-Credentials", "true");
                }
                if (!config.getExposedHeaders().isEmpty()) {
                    res.setHeader("Access-Control-Expose-Headers", join(config.getExposedHeaders()));
                }
            }
        }

        // Preflight — respond immediately without running the route handler
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            res.setHeader("Access-Control-Allow-Methods", join(config.getAllowedMethods()));
            res.setHeader("Access-Control-Allow-Headers", join(config.getAllowedHeaders()));
            res.setHeader("Access-Control-Max-Age", String.valueOf(config.getMaxAge()));
            res.status(204).sendEmpty();
            return;
        }

        chain.proceed(req, res);
    }

    private String resolveOrigin(String requestOrigin) {
        List<String> allowed = config.getAllowedOrigins();
        if (allowed.contains("*")) return "*";
        if (allowed.contains(requestOrigin)) return requestOrigin;
        return null;
    }

    private static String join(List<String> values) {
        return String.join(", ", values);
    }
}
