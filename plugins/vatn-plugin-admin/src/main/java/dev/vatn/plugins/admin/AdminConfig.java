package dev.vatn.plugins.admin;

/**
 * Configuration for {@link AdminPlugin}.
 *
 * <p>Three modes:
 * <ul>
 *   <li><strong>Default</strong> — JWT auth always active. If {@code VATN_ADMIN_TOKEN}
 *       is set, it is also accepted as a static bearer token.</li>
 *   <li><strong>Open</strong> ({@link #open()}) — no auth check at all.
 *       Suitable for localhost or trusted internal networks.</li>
 * </ul>
 *
 * <pre>{@code
 * // Default: JWT from /auth/login always required
 * new AdminPlugin()
 *
 * // JWT + static bearer token
 * new AdminPlugin(AdminConfig.defaults().withToken("my-secret"))
 *
 * // No auth gate (localhost / internal network only)
 * new AdminPlugin(AdminConfig.open())
 * }</pre>
 */
public final class AdminConfig {

    private final String  token;
    private final String  basePath;
    private final int     workflowRunLimit;
    private final boolean open;

    private AdminConfig(String token, String basePath, int workflowRunLimit, boolean open) {
        this.token            = token;
        this.basePath         = basePath;
        this.workflowRunLimit = workflowRunLimit;
        this.open             = open;
    }

    /** Reads static token from {@code VATN_ADMIN_TOKEN} environment variable (optional). JWT is always required. */
    public static AdminConfig defaults() {
        return new AdminConfig(System.getenv("VATN_ADMIN_TOKEN"), "/vatn/admin", 20, false);
    }

    /** No auth check — suitable for localhost or trusted internal networks only. */
    public static AdminConfig open() {
        return new AdminConfig(null, "/vatn/admin", 20, true);
    }

    public AdminConfig withToken(String token) {
        return new AdminConfig(token, basePath, workflowRunLimit, open);
    }

    public AdminConfig withBasePath(String path) {
        return new AdminConfig(token, path, workflowRunLimit, open);
    }

    public AdminConfig withWorkflowRunLimit(int limit) {
        return new AdminConfig(token, basePath, limit, open);
    }

    /** Returns the static bearer token, or {@code null} if not configured. */
    public String  getToken()            { return token; }
    public String  getBasePath()         { return basePath; }
    public int     getWorkflowRunLimit() { return workflowRunLimit; }
    /** True if JWT (and optionally static token) checks are enforced. */
    public boolean isAuthEnabled()       { return !open; }
}
