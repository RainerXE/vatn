package dev.vatn.plugins.security;

/**
 * Configuration for {@link SecurityPlugin}.
 *
 * <p>All fields have safe production defaults. The only field intentionally
 * left null by default is {@code hsts} (Strict-Transport-Security) and
 * {@code csp} (Content-Security-Policy) because their correct values are
 * application-specific.
 *
 * <pre>{@code
 * SecurityConfig config = SecurityConfig.defaults()
 *     .withCsp("default-src 'self'")
 *     .withHsts("max-age=31536000; includeSubDomains");
 * }</pre>
 */
public final class SecurityConfig {

    private final String frameOptions;
    private final boolean contentTypeOptions;
    private final boolean xssProtection;
    private final String hsts;
    private final String csp;
    private final String referrerPolicy;
    private final String permissionsPolicy;

    private SecurityConfig(Builder b) {
        this.frameOptions       = b.frameOptions;
        this.contentTypeOptions = b.contentTypeOptions;
        this.xssProtection      = b.xssProtection;
        this.hsts               = b.hsts;
        this.csp                = b.csp;
        this.referrerPolicy     = b.referrerPolicy;
        this.permissionsPolicy  = b.permissionsPolicy;
    }

    /** Safe defaults: SAMEORIGIN, nosniff, XSS protection, strict referrer policy. */
    public static SecurityConfig defaults() {
        return new Builder().build();
    }

    public String getFrameOptions()      { return frameOptions; }
    public boolean isContentTypeOptions(){ return contentTypeOptions; }
    public boolean isXssProtection()     { return xssProtection; }
    public String getHsts()              { return hsts; }
    public String getCsp()               { return csp; }
    public String getReferrerPolicy()    { return referrerPolicy; }
    public String getPermissionsPolicy() { return permissionsPolicy; }

    // ── fluent withers ────────────────────────────────────────────────────────

    public SecurityConfig withFrameOptions(String value)      { return toBuilder().frameOptions(value).build(); }
    public SecurityConfig withHsts(String value)              { return toBuilder().hsts(value).build(); }
    public SecurityConfig withCsp(String value)               { return toBuilder().csp(value).build(); }
    public SecurityConfig withReferrerPolicy(String value)    { return toBuilder().referrerPolicy(value).build(); }
    public SecurityConfig withPermissionsPolicy(String value) { return toBuilder().permissionsPolicy(value).build(); }
    public SecurityConfig withoutXssProtection()              { return toBuilder().xssProtection(false).build(); }
    public SecurityConfig withoutContentTypeOptions()         { return toBuilder().contentTypeOptions(false).build(); }

    private Builder toBuilder() {
        return new Builder()
            .frameOptions(frameOptions)
            .contentTypeOptions(contentTypeOptions)
            .xssProtection(xssProtection)
            .hsts(hsts)
            .csp(csp)
            .referrerPolicy(referrerPolicy)
            .permissionsPolicy(permissionsPolicy);
    }

    // ── builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private String  frameOptions       = "SAMEORIGIN";
        private boolean contentTypeOptions = true;
        private boolean xssProtection      = true;
        private String  hsts               = null;
        private String  csp                = null;
        private String  referrerPolicy     = "strict-origin-when-cross-origin";
        private String  permissionsPolicy  = null;

        public Builder frameOptions(String v)       { frameOptions = v;       return this; }
        public Builder contentTypeOptions(boolean v){ contentTypeOptions = v; return this; }
        public Builder xssProtection(boolean v)     { xssProtection = v;     return this; }
        public Builder hsts(String v)               { hsts = v;              return this; }
        public Builder csp(String v)                { csp = v;               return this; }
        public Builder referrerPolicy(String v)     { referrerPolicy = v;    return this; }
        public Builder permissionsPolicy(String v)  { permissionsPolicy = v; return this; }
        public SecurityConfig build()               { return new SecurityConfig(this); }
    }
}
