package dev.vatn.api;

/**
 * Transport-neutral HTTP response. Extends VResponse with HTTP-specific helpers
 * and a fluent status setter for the common {@code res.status(500).send(...)} pattern.
 */
@VatnApi(since = "1.0")
public interface VHttpResponse extends VResponse {

    /**
     * Sets the HTTP status code and returns {@code this} for fluent chaining:
     * {@code res.status(404).send("Not found")}.
     */
    VHttpResponse status(int code);

    /** Sends raw bytes as the response body. */
    void send(byte[] content);

    // ── convenient defaults ───────────────────────────────────────────────────

    /** Fluent header setter — {@code res.header("X-Foo", "bar").send(...)}. */
    default VHttpResponse header(String name, String value) {
        setHeader(name, value);
        return this;
    }

    /** Sets Content-Type to {@code application/json} and sends the JSON string. */
    default void sendJson(String json) {
        setHeader("Content-Type", "application/json");
        send(json);
    }

    /** Sets Content-Type to {@code text/html;charset=UTF-8} and sends the HTML string. */
    default void sendHtml(String html) {
        setHeader("Content-Type", "text/html;charset=UTF-8");
        send(html);
    }

    /** Sends a {@code 204 No Content} response. */
    default void sendEmpty() {
        status(204);
        send("");
    }

    /**
     * Sends a {@code 302 Found} redirect to {@code url}.
     * {@code res.redirect("/login")} is equivalent to
     * {@code res.status(302).setHeader("Location", url).send("")}.
     */
    default void redirect(String url) {
        status(302);
        setHeader("Location", url);
        send("");
    }

    /**
     * Sets a {@code Set-Cookie} header with sensible defaults ({@link CookieOptions#defaults()}).
     *
     * <p><b>Note:</b> the default implementation calls {@link #setHeader}, which replaces any
     * previously set {@code Set-Cookie} header. If you need to set multiple cookies in one
     * response, call {@link #setCookie(String, String, CookieOptions)} for each and consider
     * overriding with an {@code addHeader} implementation.
     */
    default void setCookie(String name, String value) {
        setCookie(name, value, CookieOptions.defaults());
    }

    /** Sets a {@code Set-Cookie} header with the given {@link CookieOptions}. */
    default void setCookie(String name, String value, CookieOptions options) {
        setHeader("Set-Cookie", options.toHeaderValue(name, value));
    }
}
