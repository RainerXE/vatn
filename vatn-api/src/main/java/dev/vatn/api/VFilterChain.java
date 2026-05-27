package dev.vatn.api;

/**
 * Represents the remaining portion of a {@link VHttpFilter} chain.
 *
 * <p>Call {@link #proceed} inside a filter's {@code doFilter} to pass control
 * to the next filter in the chain, or to the route handler if no filters remain.
 */
@VatnApi(since = "1.0")
@FunctionalInterface
public interface VFilterChain {

    /**
     * Advance to the next filter, or to the route handler if this is the last step.
     *
     * @param req the (possibly modified) request
     * @param res the (possibly modified) response
     * @throws Exception propagated from downstream filters or the route handler
     */
    void proceed(VHttpRequest req, VHttpResponse res) throws Exception;
}
