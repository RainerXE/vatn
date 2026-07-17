package dev.vatn.plugins.cors;

import java.util.List;

/**
 * CORS policy configuration.
 *
 * <pre>{@code
 * // Permissive (development)
 * CorsConfig.permissive()
 *
 * // Production: specific origins only
 * CorsConfig.of("https://app.example.com", "https://admin.example.com")
 *     .withCredentials(true)
 *     .withMaxAge(3600)
 * }</pre>
 */
public final class CorsConfig {

    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;
    private final List<String> exposedHeaders;
    private final boolean allowCredentials;
    private final int maxAge;

    private CorsConfig(List<String> allowedOrigins, List<String> allowedMethods,
                       List<String> allowedHeaders, List<String> exposedHeaders,
                       boolean allowCredentials, int maxAge) {
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
        this.exposedHeaders = exposedHeaders;
        this.allowCredentials = allowCredentials;
        this.maxAge = maxAge;
    }

    /** Allow all origins — suitable for public APIs and local development. */
    public static CorsConfig permissive() {
        return new CorsConfig(
                List.of("*"),
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
                List.of("*"),
                List.of(),
                false, 3600);
    }

    /** Allow only the listed origins. */
    public static CorsConfig of(String... origins) {
        return new CorsConfig(
                List.of(origins),
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
                List.of("Content-Type", "Authorization", "X-Requested-With"),
                List.of(),
                false, 3600);
    }

    public CorsConfig withMethods(String... methods)  { return new CorsConfig(allowedOrigins, List.of(methods), allowedHeaders, exposedHeaders, allowCredentials, maxAge); }
    public CorsConfig withHeaders(String... headers)  { return new CorsConfig(allowedOrigins, allowedMethods, List.of(headers), exposedHeaders, allowCredentials, maxAge); }
    public CorsConfig withExposed(String... headers)  { return new CorsConfig(allowedOrigins, allowedMethods, allowedHeaders, List.of(headers), allowCredentials, maxAge); }
    public CorsConfig withCredentials(boolean allow)  { return new CorsConfig(allowedOrigins, allowedMethods, allowedHeaders, exposedHeaders, allow, maxAge); }
    public CorsConfig withMaxAge(int seconds)         { return new CorsConfig(allowedOrigins, allowedMethods, allowedHeaders, exposedHeaders, allowCredentials, seconds); }

    public List<String> getAllowedOrigins()  { return allowedOrigins; }
    public List<String> getAllowedMethods()  { return allowedMethods; }
    public List<String> getAllowedHeaders()  { return allowedHeaders; }
    public List<String> getExposedHeaders() { return exposedHeaders; }
    public boolean isAllowCredentials()     { return allowCredentials; }
    public int getMaxAge()                  { return maxAge; }
}
