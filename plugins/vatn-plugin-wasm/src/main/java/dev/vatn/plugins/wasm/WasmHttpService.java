package dev.vatn.plugins.wasm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VWasmRuntime;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * HTTP management surface for the WASM plugin, mounted at {@code /wasm}.
 *
 * <pre>
 * GET  /wasm/modules                     — list loaded modules + engine info
 * GET  /wasm/modules/{id}/exports        — list exports of a module
 * POST /wasm/modules/{id}/load           — load a module from base64-encoded bytes
 * DELETE /wasm/modules/{id}              — unload a module
 * POST /wasm/modules/{id}/call/{fn}      — call an integer export
 * POST /wasm/modules/{id}/wasi           — run a WASI module
 * </pre>
 */
class WasmHttpService implements VHttpService {

    private final VWasmRuntime runtime;
    private final ObjectMapper mapper = new ObjectMapper();

    WasmHttpService(VWasmRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void routing(VHttpRoutes routes) {
        routes.get("/modules",                       this::handleList);
        routes.get("/modules/{id}/exports",          this::handleExports);
        routes.post("/modules/{id}/load",            this::handleLoad);
        routes.delete("/modules/{id}",               this::handleUnload);
        routes.post("/modules/{id}/call/{fn}",       this::handleCall);
        routes.post("/modules/{id}/wasi",            this::handleWasi);
    }

    // GET /wasm/modules
    private void handleList(VHttpRequest req, VHttpResponse res) throws Exception {
        List<String> ids = runtime.listModules();
        res.sendJson(mapper.writeValueAsString(Map.of(
            "runtime", runtime.runtimeName(),
            "version", runtime.runtimeVersion(),
            "modules", ids,
            "count",   ids.size()
        )));
    }

    // GET /wasm/modules/{id}/exports
    private void handleExports(VHttpRequest req, VHttpResponse res) throws Exception {
        String id = req.getPathParam("id");
        runtime.get(id).ifPresentOrElse(
            m -> res.sendJson(toJson(Map.of("id", id, "exports", m.exports()))),
            () -> res.status(404).sendJson("{\"error\":\"Module '" + id + "' not loaded\"}")
        );
    }

    // POST /wasm/modules/{id}/load  body: {"bytes":"<base64>"}
    private void handleLoad(VHttpRequest req, VHttpResponse res) throws Exception {
        String id   = req.getPathParam("id");
        Map<?, ?> body = mapper.readValue(req.getBody(), Map.class);
        String b64  = (String) body.get("bytes");
        if (b64 == null || b64.isBlank()) {
            res.status(400).sendJson("{\"error\":\"'bytes' (base64) is required\"}");
            return;
        }
        byte[] wasmBytes = Base64.getDecoder().decode(b64);
        var module = runtime.load(id, wasmBytes);
        res.status(201).sendJson(toJson(Map.of(
            "id",      id,
            "exports", module.exports(),
            "status",  "loaded"
        )));
    }

    // DELETE /wasm/modules/{id}
    private void handleUnload(VHttpRequest req, VHttpResponse res) throws Exception {
        String id = req.getPathParam("id");
        runtime.unload(id);
        res.sendJson("{\"status\":\"unloaded\",\"id\":\"" + id + "\"}");
    }

    // POST /wasm/modules/{id}/call/{fn}  body: {"args":[1,2,3]}
    @SuppressWarnings({"unchecked","rawtypes"})
    private void handleCall(VHttpRequest req, VHttpResponse res) throws Exception {
        String id = req.getPathParam("id");
        String fn = req.getPathParam("fn");
        runtime.get(id).ifPresentOrElse(m -> {
            try {
                Map body    = mapper.readValue(req.getBody(), Map.class);
                Object argsRaw = body.get("args");
                List argList = argsRaw instanceof List ? (List) argsRaw : java.util.List.of();
                long[] args = argList.stream()
                    .mapToLong(v -> ((Number) v).longValue())
                    .toArray();
                long[] results = m.call(fn, args);
                res.sendJson(toJson(Map.of("results", results)));
            } catch (Exception e) {
                res.status(500).sendJson("{\"error\":\"" + e.getMessage() + "\"}");
            }
        }, () -> res.status(404).sendJson("{\"error\":\"Module '" + id + "' not loaded\"}"));
    }

    // POST /wasm/modules/{id}/wasi  body: {"argv":["prog","--flag"],"env":{"K":"V"}}
    @SuppressWarnings({"unchecked","rawtypes"})
    private void handleWasi(VHttpRequest req, VHttpResponse res) throws Exception {
        String id = req.getPathParam("id");
        runtime.get(id).ifPresentOrElse(m -> {
            try {
                Map body    = mapper.readValue(req.getBody(), Map.class);
                Object argvRaw = body.get("argv");
                List<String> argv = argvRaw instanceof List
                    ? (List<String>) argvRaw
                    : java.util.List.of("wasm");
                Map<String, String> env = (Map<String, String>) body.get("env");
                String output = m.callWasi(argv.toArray(String[]::new), env);
                res.sendJson(toJson(Map.of("output", output)));
            } catch (dev.vatn.api.VWasmCallException e) {
                res.status(500).sendJson(toJson(Map.of(
                    "error",    e.getMessage(),
                    "exitCode", e.exitCode()
                )));
            } catch (Exception e) {
                res.status(500).sendJson("{\"error\":\"" + e.getMessage() + "\"}");
            }
        }, () -> res.status(404).sendJson("{\"error\":\"Module '" + id + "' not loaded\"}"));
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }
}
