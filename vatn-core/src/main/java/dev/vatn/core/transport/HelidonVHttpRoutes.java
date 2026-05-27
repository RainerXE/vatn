package dev.vatn.core.transport;

import dev.vatn.api.VHttpHandler;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VSseHandler;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.sse.SseSink;

/** Wraps Helidon HttpRules as the VATN VHttpRoutes SPI. */
public class HelidonVHttpRoutes implements VHttpRoutes {

    private final HttpRules rules;

    public HelidonVHttpRoutes(HttpRules rules) {
        this.rules = rules;
    }

    @Override
    public VHttpRoutes get(String path, VHttpHandler handler) {
        rules.get(path, (req, res) -> handler.handle(
            new HelidonVHttpRequest(req), new HelidonVHttpResponse(res)));
        return this;
    }

    @Override
    public VHttpRoutes post(String path, VHttpHandler handler) {
        rules.post(path, (req, res) -> handler.handle(
            new HelidonVHttpRequest(req), new HelidonVHttpResponse(res)));
        return this;
    }

    @Override
    public VHttpRoutes put(String path, VHttpHandler handler) {
        rules.put(path, (req, res) -> handler.handle(
            new HelidonVHttpRequest(req), new HelidonVHttpResponse(res)));
        return this;
    }

    @Override
    public VHttpRoutes delete(String path, VHttpHandler handler) {
        rules.delete(path, (req, res) -> handler.handle(
            new HelidonVHttpRequest(req), new HelidonVHttpResponse(res)));
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
        rules.register(path, new HelidonVHttpServiceAdapter(service));
        return this;
    }
}
