package dev.vatn.core.transport;

import dev.vatn.api.VFilterChain;
import dev.vatn.api.VHttpFilter;
import dev.vatn.api.VHttpHandler;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VSseHandler;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.sse.SseSink;

import java.util.List;

/** Wraps Helidon HttpRules as the VATN VHttpRoutes SPI, running registered filters before each handler. */
public class HelidonVHttpRoutes implements VHttpRoutes {

    private final HttpRules rules;
    private final List<VHttpFilter> filters;

    public HelidonVHttpRoutes(HttpRules rules) {
        this(rules, List.of());
    }

    public HelidonVHttpRoutes(HttpRules rules, List<VHttpFilter> filters) {
        this.rules = rules;
        this.filters = filters;
    }

    @Override
    public VHttpRoutes get(String path, VHttpHandler handler) {
        rules.get(path, (req, res) -> runChain(
                new HelidonVHttpRequest(req), new HelidonVHttpResponse(res), handler));
        return this;
    }

    @Override
    public VHttpRoutes post(String path, VHttpHandler handler) {
        rules.post(path, (req, res) -> runChain(
                new HelidonVHttpRequest(req), new HelidonVHttpResponse(res), handler));
        return this;
    }

    @Override
    public VHttpRoutes put(String path, VHttpHandler handler) {
        rules.put(path, (req, res) -> runChain(
                new HelidonVHttpRequest(req), new HelidonVHttpResponse(res), handler));
        return this;
    }

    @Override
    public VHttpRoutes delete(String path, VHttpHandler handler) {
        rules.delete(path, (req, res) -> runChain(
                new HelidonVHttpRequest(req), new HelidonVHttpResponse(res), handler));
        return this;
    }

    @Override
    public VHttpRoutes patch(String path, VHttpHandler handler) {
        rules.patch(path, (req, res) -> runChain(
                new HelidonVHttpRequest(req), new HelidonVHttpResponse(res), handler));
        return this;
    }

    @Override
    public VHttpRoutes options(String path, VHttpHandler handler) {
        rules.options(path, (req, res) -> runChain(
                new HelidonVHttpRequest(req), new HelidonVHttpResponse(res), handler));
        return this;
    }

    @Override
    public VHttpRoutes sse(String path, VSseHandler handler) {
        rules.get(path, (req, res) -> {
            try (SseSink sink = res.sink(SseSink.TYPE)) {
                handler.handle(new HelidonVHttpRequest(req), new HelidonVSseSink(sink));
            }
        });
        return this;
    }

    @Override
    public VHttpRoutes register(String path, VHttpService service) {
        rules.register(path, new HelidonVHttpServiceAdapter(service, filters));
        return this;
    }

    // ── filter chain ─────────────────────────────────────────────────────────

    private void runChain(VHttpRequest req, VHttpResponse res, VHttpHandler terminal) throws Exception {
        buildChain(0, terminal).proceed(req, res);
    }

    private VFilterChain buildChain(int index, VHttpHandler terminal) {
        if (index >= filters.size()) {
            return (req, res) -> terminal.handle(req, res);
        }
        VHttpFilter current = filters.get(index);
        VFilterChain rest = buildChain(index + 1, terminal);
        return (req, res) -> current.doFilter(req, res, rest);
    }
}
