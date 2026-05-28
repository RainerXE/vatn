package dev.vatn.core.transport;

import dev.vatn.api.VFilterChain;
import dev.vatn.api.VHttpFilter;
import dev.vatn.api.VHttpHandler;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class VHttpFilterChainTest {

    // ── stubs ─────────────────────────────────────────────────────────────────

    static class StubRequest implements VHttpRequest {
        private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

        @Override public String getPathParam(String name)                 { return null; }
        @Override public String getQueryParam(String name, String def)    { return def; }
        @Override public String getMethod()                               { return "GET"; }
        @Override public String getPath()                                 { return "/test"; }
        @Override public byte[] getBodyBytes()                            { return new byte[0]; }
        @Override public Map<String, String> getHeaders()                 { return Map.of(); }
        @Override public String getHeader(String name)                    { return null; }
        @Override public String getBody()                                 { return ""; }
        @Override public String getSourceId()                             { return "stub"; }

        @Override
        public void setAttribute(String key, Object value) {
            attributes.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> getAttribute(String key, Class<T> type) {
            Object val = attributes.get(key);
            if (val == null) return Optional.empty();
            if (!type.isInstance(val)) return Optional.empty();
            return Optional.of(type.cast(val));
        }
    }

    static class StubResponse implements VHttpResponse {
        private final List<String> written = new ArrayList<>();
        private int statusCode = 200;

        @Override public void setHeader(String name, String value) {}
        @Override public void send(String content)  { written.add(content); }
        @Override public void send(byte[] content)  {}
        @Override public void setStatus(int code)   { statusCode = code; }
        @Override public VHttpResponse status(int code) { statusCode = code; return this; }

        List<String> written()   { return written; }
        int statusCode()         { return statusCode; }
    }

    // ── chain builder (mirrors HelidonVHttpRoutes.buildChain) ────────────────

    private VFilterChain buildChain(List<VHttpFilter> filters, int index, VHttpHandler terminal) {
        if (index >= filters.size()) return (req, res) -> terminal.handle(req, res);
        VHttpFilter current = filters.get(index);
        VFilterChain rest = buildChain(filters, index + 1, terminal);
        return (req, res) -> current.doFilter(req, res, rest);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void singleFilter_runsBeforeHandler() throws Exception {
        List<String> order = new ArrayList<>();

        VHttpFilter filter = new VHttpFilter() {
            @Override public int order() { return 1; }
            @Override public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
                order.add("filter1");
                chain.proceed(req, res);
            }
        };

        VHttpHandler handler = (req, res) -> order.add("handler");

        VFilterChain chain = buildChain(List.of(filter), 0, handler);
        chain.proceed(new StubRequest(), new StubResponse());

        assertEquals(List.of("filter1", "handler"), order);
    }

    @Test
    void multipleFilters_runInOrderThenHandler() throws Exception {
        List<String> order = new ArrayList<>();

        VHttpFilter f1 = new VHttpFilter() {
            @Override public int order() { return 1; }
            @Override public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
                order.add("f1");
                chain.proceed(req, res);
            }
        };
        VHttpFilter f2 = new VHttpFilter() {
            @Override public int order() { return 2; }
            @Override public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
                order.add("f2");
                chain.proceed(req, res);
            }
        };
        VHttpFilter f3 = new VHttpFilter() {
            @Override public int order() { return 3; }
            @Override public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) throws Exception {
                order.add("f3");
                chain.proceed(req, res);
            }
        };

        List<VHttpFilter> unsorted = new ArrayList<>(List.of(f2, f1, f3));
        unsorted.sort(java.util.Comparator.comparingInt(VHttpFilter::order));

        VHttpHandler handler = (req, res) -> order.add("handler");

        VFilterChain chain = buildChain(unsorted, 0, handler);
        chain.proceed(new StubRequest(), new StubResponse());

        assertEquals(List.of("f1", "f2", "f3", "handler"), order);
    }

    @Test
    void filterCanShortCircuit() throws Exception {
        List<String> reached = new ArrayList<>();

        VHttpFilter blocker = new VHttpFilter() {
            @Override public int order() { return 1; }
            @Override public void doFilter(VHttpRequest req, VHttpResponse res, VFilterChain chain) {
                res.send("blocked");
            }
        };

        VHttpHandler handler = (req, res) -> reached.add("handler");

        VFilterChain chain = buildChain(List.of(blocker), 0, handler);
        StubResponse res = new StubResponse();
        chain.proceed(new StubRequest(), res);

        assertTrue(reached.isEmpty(), "Handler should not run when filter short-circuits");
        assertEquals(List.of("blocked"), res.written());
    }

    @Test
    void attributeSetAndGetRoundTrip() {
        StubRequest req = new StubRequest();

        req.setAttribute("user-id", "alice");

        Optional<String> found = req.getAttribute("user-id", String.class);
        assertTrue(found.isPresent());
        assertEquals("alice", found.get());

        Optional<Integer> wrongType = req.getAttribute("user-id", Integer.class);
        assertTrue(wrongType.isEmpty(), "getAttribute with wrong type should return empty");

        Optional<String> missing = req.getAttribute("non-existent", String.class);
        assertTrue(missing.isEmpty(), "getAttribute for absent key should return empty");
    }
}
