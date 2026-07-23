package dev.vatn.plugins.devenv;

import dev.vatn.api.VHttpRequest;
import dev.vatn.api.VHttpResponse;
import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VJson;
import dev.vatn.plugins.devenv.model.DevEnvSnapshot;

/**
 * REST surface for the DevEnv plugin, mounted at {@code /devenv}. Serializes snapshots with the
 * host {@link VJson} (DCN-DEV-10).
 */
final class DevEnvHttpService implements VHttpService {

    private final DevEnvService svc;
    private final VJson json;

    DevEnvHttpService(DevEnvService svc, VJson json) {
        this.svc = svc;
        this.json = json;
    }

    @Override
    public void routing(VHttpRoutes routes) {
        routes.get("/snapshot", this::handleSnapshot);
        routes.post("/scan", this::handleScan);
        routes.post("/refresh", this::handleRefresh);
        routes.get("/runtimes", this::handleRuntimes);
        routes.get("/packages", this::handlePackages);
        routes.get("/venvs", this::handleVenvs);
        routes.get("/containers", this::handleContainers);
        routes.get("/kubernetes", this::handleKubernetes);
        routes.get("/agents", this::handleAgents);
        routes.get("/llm", this::handleLlm);
        routes.get("/accelerators", this::handleAccelerators);
        routes.get("/apple", this::handleApple);
        routes.get("/health", this::handleHealth);
    }

    private void handleSnapshot(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan()));
    }

    private void handleScan(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.scan()));
    }

    private void handleRefresh(VHttpRequest req, VHttpResponse res) {
        svc.refresh();
        res.sendJson("{\"status\":\"refresh scheduled\"}");
    }

    private void handleRuntimes(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().runtimesSlice()));
    }

    private void handlePackages(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().packages()));
    }

    private void handleVenvs(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().venvs()));
    }

    private void handleContainers(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().containers()));
    }

    private void handleKubernetes(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().kubernetes()));
    }

    private void handleAgents(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().agentsSlice()));
    }

    private void handleLlm(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().llm()));
    }

    private void handleAccelerators(VHttpRequest req, VHttpResponse res) {
        res.sendJson(json.stringify(svc.snapshotOrScan().accelerators()));
    }

    private void handleApple(VHttpRequest req, VHttpResponse res) {
        var apple = svc.snapshotOrScan().apple();
        if (apple == null) {
            res.status(204).sendEmpty();
        } else {
            res.sendJson(json.stringify(apple));
        }
    }

    private void handleHealth(VHttpRequest req, VHttpResponse res) {
        var last = svc.lastSnapshot();
        String scannedAt = last.map(DevEnvSnapshot::scannedAt).orElse("none");
        res.sendJson(String.format("{\"status\":\"ok\",\"hasSnapshot\":%b,\"lastScan\":\"%s\"}",
                last.isPresent(), scannedAt));
    }
}
