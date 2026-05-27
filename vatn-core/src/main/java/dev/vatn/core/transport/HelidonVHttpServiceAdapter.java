package dev.vatn.core.transport;

import dev.vatn.api.VHttpService;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;

/**
 * Adapts a VATN VHttpService to a Helidon HttpService.
 * This is the only place in vatn-core that bridges the two worlds.
 * All callers above this class (vatn-api consumers) remain Helidon-free.
 */
public class HelidonVHttpServiceAdapter implements HttpService {

    private final VHttpService delegate;

    public HelidonVHttpServiceAdapter(VHttpService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void routing(HttpRules rules) {
        delegate.routing(new HelidonVHttpRoutes(rules));
    }
}
