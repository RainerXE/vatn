package dev.vatn.api;

/**
 * Intercepts every HTTP request/response passing through a VATN node's plugin routes.
 *
 * <p>Filters run in ascending {@link #order()} before the actual route handler.
 * Register a filter via {@link VNodeContext#registerFilter(VHttpFilter)}.
 *
 * <p>Use {@link VFilterChain#proceed} to pass control to the next filter or,
 * if no more filters remain, to the route handler itself.
 *
 * <p>Pre-defined order constants are provided as a guide — any integer is valid.
 *
 * <pre>{@code
 * public class CorsFilter implements VHttpFilter {
 *     public int order() { return VHttpFilter.SECURITY; }
 *
 *     public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
 *         res.setHeader("Access-Control-Allow-Origin", "*");
 *         chain.proceed(req, res);
 *     }
 * }
 *
 * // In your plugin:
 * ctx.registerFilter(new CorsFilter());
 * }</pre>
 */
@VatnApi(since = "1.0")
public interface VHttpFilter extends VService {

    /** Recommended order slot for distributed tracing filters. */
    int TRACING    = 100;
    /** Recommended order slot for security-header filters. */
    int SECURITY   = 200;
    /** Recommended order slot for authentication/authorisation filters. */
    int AUTH       = 300;
    /** Recommended order slot for rate-limiting filters. */
    int RATE_LIMIT = 400;
    /** Recommended order slot for request/response logging filters. */
    int LOGGING    = 900;

    /**
     * Position in the filter chain. Lower values run first.
     * Use the named constants above as anchors.
     */
    int order();

    /**
     * Process the request/response pair.
     *
     * <p>Call {@code chain.proceed(req, res)} to continue to the next filter
     * or to the route handler. Omitting the call short-circuits the chain —
     * useful for auth failures or preflight responses.
     *
     * @param req   the incoming HTTP request
     * @param res   the outgoing HTTP response
     * @param chain the remainder of the filter chain
     * @throws Exception on any error; unchecked exceptions propagate as-is
     */
    void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception;
}
