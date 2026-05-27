package dev.vatn.api;

/**
 * Fluent route registration, transport-neutral. Passed to {@link VHttpService#routing(VHttpRoutes)}
 * so services can declare their routes without importing any runtime types.
 *
 * Path templates use {name} syntax: {@code "/users/{id}"}.
 */
@VatnApi(since = "1.0")
public interface VHttpRoutes {

    VHttpRoutes get(String path, VHttpHandler handler);

    VHttpRoutes post(String path, VHttpHandler handler);

    VHttpRoutes put(String path, VHttpHandler handler);

    VHttpRoutes delete(String path, VHttpHandler handler);

    VHttpRoutes patch(String path, VHttpHandler handler);

    /** Handles HTTP OPTIONS requests — CORS preflight, capability discovery. */
    VHttpRoutes options(String path, VHttpHandler handler);

    /** Registers a Server-Sent Events endpoint at the given path (GET). */
    VHttpRoutes sse(String path, VSseHandler handler);

    /** Mounts a sub-service at the given path prefix. */
    VHttpRoutes register(String path, VHttpService service);
}
