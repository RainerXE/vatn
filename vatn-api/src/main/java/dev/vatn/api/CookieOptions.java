package dev.vatn.api;

/**
 * Attributes for an HTTP {@code Set-Cookie} response header.
 *
 * <pre>{@code
 * res.setCookie("session", token, CookieOptions.defaults().withMaxAge(3600));
 * res.setCookie("remember", "1",  CookieOptions.secure().withMaxAge(2592000));
 * }</pre>
 */
@VatnApi(since = "1.0")
public final class CookieOptions {

    private final String  path;
    private final String  domain;
    private final int     maxAge;    // -1 = session cookie (no Max-Age emitted)
    private final boolean httpOnly;
    private final boolean secure;
    private final String  sameSite;  // "Strict", "Lax", "None"

    private CookieOptions(String path, String domain, int maxAge,
                          boolean httpOnly, boolean secure, String sameSite) {
        this.path     = path;
        this.domain   = domain;
        this.maxAge   = maxAge;
        this.httpOnly = httpOnly;
        this.secure   = secure;
        this.sameSite = sameSite;
    }

    /** {@code Path=/; HttpOnly; SameSite=Lax} — sensible default for most cookies. */
    public static CookieOptions defaults() {
        return new CookieOptions("/", null, -1, true, false, "Lax");
    }

    /** {@code Path=/; HttpOnly; Secure; SameSite=Strict} — for session/auth cookies over HTTPS. */
    public static CookieOptions secure() {
        return new CookieOptions("/", null, -1, true, true, "Strict");
    }

    public CookieOptions withPath(String path)       { return new CookieOptions(path, domain, maxAge, httpOnly, secure, sameSite); }
    public CookieOptions withDomain(String domain)   { return new CookieOptions(path, domain, maxAge, httpOnly, secure, sameSite); }
    public CookieOptions withMaxAge(int seconds)     { return new CookieOptions(path, domain, seconds, httpOnly, secure, sameSite); }
    public CookieOptions withHttpOnly(boolean v)     { return new CookieOptions(path, domain, maxAge, v,        secure, sameSite); }
    public CookieOptions withSecure(boolean v)       { return new CookieOptions(path, domain, maxAge, httpOnly, v,      sameSite); }
    public CookieOptions withSameSite(String policy) { return new CookieOptions(path, domain, maxAge, httpOnly, secure, policy);   }

    String toHeaderValue(String name, String value) {
        StringBuilder sb = new StringBuilder(name).append('=').append(value);
        if (path != null)     sb.append("; Path=").append(path);
        if (domain != null)   sb.append("; Domain=").append(domain);
        if (maxAge >= 0)      sb.append("; Max-Age=").append(maxAge);
        if (httpOnly)         sb.append("; HttpOnly");
        if (secure)           sb.append("; Secure");
        if (sameSite != null) sb.append("; SameSite=").append(sameSite);
        return sb.toString();
    }
}
