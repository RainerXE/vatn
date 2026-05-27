package dev.vatn.api;

/**
 * Handler for a single HTTP route. Used as a lambda or method reference
 * when registering routes via {@link VHttpRoutes}.
 */
@FunctionalInterface
@VatnApi(since = "1.0")
public interface VHttpHandler {
    void handle(VHttpRequest request, VHttpResponse response) throws Exception;
}
