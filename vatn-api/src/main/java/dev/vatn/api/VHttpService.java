package dev.vatn.api;

/**
 * Transport-neutral HTTP service. Implement this interface to register REST endpoints
 * via {@link VNodeContext#register(String, VHttpService)} without importing any
 * runtime-specific types (Helidon, Vert.x, etc.).
 *
 * The vatn-core Helidon adapter wraps implementations of this interface transparently.
 *
 * Example:
 * <pre>
 * public class StatusService implements VHttpService {
 *     {@literal @}Override
 *     public void routing(VHttpRoutes routes) {
 *         routes.get("/status", (req, res) -> res.send("UP"));
 *     }
 * }
 * // In your VNodePlugin:
 * context.register("/api", new StatusService());
 * </pre>
 */
@VatnApi(since = "1.0")
public interface VHttpService extends VService {
    void routing(VHttpRoutes routes);
}
