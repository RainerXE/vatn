package dev.vatn.plugins.admin;

/**
 * Configuration for {@link AdminPlugin}.
 *
 * <pre>{@code
 * // Minimal: reads VATN_ADMIN_TOKEN from environment
 * new AdminPlugin()
 *
 * // Explicit token and custom path
 * new AdminPlugin(AdminConfig.defaults()
 *     .withToken("my-secret")
 *     .withBasePath("/admin"))
 *
 * // No auth gate (localhost / internal network only)
 * new AdminPlugin(AdminConfig.open())
 * }</pre>
 */
public final class AdminConfig {

    private final String token;
    private final String basePath;
    private final int    workflowRunLimit;

    private AdminConfig(String token, String basePath, int workflowRunLimit) {
        this.token            = token;
        this.basePath         = basePath;
        this.workflowRunLimit = workflowRunLimit;
    }

    /** Reads token from {@code VATN_ADMIN_TOKEN} environment variable. */
    public static AdminConfig defaults() {
        return new AdminConfig(System.getenv("VATN_ADMIN_TOKEN"), "/vatn/admin", 20);
    }

    /** No bearer-token check — suitable for localhost or trusted internal networks only. */
    public static AdminConfig open() {
        return new AdminConfig(null, "/vatn/admin", 20);
    }

    public AdminConfig withToken(String token) {
        return new AdminConfig(token, basePath, workflowRunLimit);
    }

    public AdminConfig withBasePath(String path) {
        return new AdminConfig(token, path, workflowRunLimit);
    }

    public AdminConfig withWorkflowRunLimit(int limit) {
        return new AdminConfig(token, basePath, limit);
    }

    /** Returns the bearer token required on all admin API requests, or {@code null} if auth is disabled. */
    public String  getToken()            { return token; }
    public String  getBasePath()         { return basePath; }
    public int     getWorkflowRunLimit() { return workflowRunLimit; }
    public boolean isAuthEnabled()       { return token != null && !token.isBlank(); }
}
