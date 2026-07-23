package dev.vatn.plugins.containers;

import dev.vatn.api.*;
import dev.vatn.api.admin.VAdminContribution;
import dev.vatn.api.admin.VAdminContributionRegistry;
import dev.vatn.api.admin.VWorkloadRegistry;
import dev.vatn.api.security.VTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static dev.vatn.plugins.containers.Sanitizer.sanitizeHtml;

public class ContainersPlugin implements VNodePlugin, VAdminContribution {
    private static final Logger log = LoggerFactory.getLogger(ContainersPlugin.class);

    private final long startedAt = System.currentTimeMillis();
    private List<ContainerManager> managers;

    @Override
    public String getId() {
        return "plugin.containers";
    }

    @Override
    public String getName() {
        return "Containers Management Plugin";
    }

    @Override
    public String getVersion() {
        return "1.0-alpha.15-preview";
    }

    @Override
    public void onInitialize(VNodeContext context) {
        // 1. Detect OS
        boolean fedoraDerivative = OsDetector.isFedoraDerivative();
        log.info("Detected OS: {} ({})", OsDetector.distributionName(),
            fedoraDerivative ? "Fedora derivative" : "other Linux");

        // 2. Initialize Managers
        VProcessService processService = context.getService(VProcessService.class).orElseThrow();
        VJson json = context.getJson();

        List<ContainerManager> mgrList = new ArrayList<>();
        mgrList.add(new GenericContainerManager(VContainerEngine.DOCKER, "docker", processService, json));
        mgrList.add(new GenericContainerManager(VContainerEngine.PODMAN, "podman", processService, json));
        mgrList.add(new DistroboxManager(processService));

        // On Fedora derivatives, also try Toolbx
        if (fedoraDerivative) {
            mgrList.add(new ToolboxManager(processService));
            log.info("Fedora derivative detected — Toolbx support enabled");
        }

        managers = List.copyOf(mgrList);

        // 3. Initialize Template Service, Resource Profile Service, and Container Creator
        TemplateService templateService = new JsonTemplateStore(context.getWorkspacePath());
        context.registerService(TemplateService.class, templateService);
        ResourceProfileService profileService = new JsonResourceProfileStore(context.getWorkspacePath());
        ContainerCreator creator = new ContainerCreator(processService, managers, profileService);

        ContainerStackService stackService = new ContainerStackStore(context.getWorkspacePath());
        StackDeployer deployer = new StackDeployer(creator, managers, processService, templateService);

        // 4. Register with Workload Registry
        context.getService(VWorkloadRegistry.class).ifPresent(registry -> {
            registry.registerProvider(new ContainersWorkloadProvider(managers));
        });

        // 3. Register with Admin Contribution Registry
        context.getService(VAdminContributionRegistry.class).ifPresent(r -> r.register(this));

        // 4. Register WebSocket Route for Web Terminal
        context.registerWebSocket("/vatn/containers/ws/exec", new WebTerminalHandler(managers));

        // 5. Register health check
        context.registerHealthCheck("containers", () -> managers != null && !managers.isEmpty());

        // 6. Register HTTP Web Dashboard UI
        context.register("/vatn/containers", routes -> {
            
            // Base Page HTML
            routes.get("", (req, res) -> res.sendHtml(ContainersHtml.render("/vatn/containers")));

            // Endpoint: Node ID
            routes.get("/api/node-id", (req, res) -> res.send(context.getNodeId()));

            // Endpoint: System Status Overview (HTML Fragment)
            routes.get("/api/system-status", (req, res) -> {
                long uptimeSec = (System.currentTimeMillis() - startedAt) / 1000;
                long h = uptimeSec / 3600;
                long m = (uptimeSec % 3600) / 60;
                long s = uptimeSec % 60;
                String uptime = String.format("%dh %dm %ds", h, m, s);

                int pluginsCount = context.getService(VPluginRegistry.class)
                    .map(r -> r.getPlugins().size()).orElse(0);
                String osName = sanitizeHtml(OsDetector.distributionName());
                
                String html = String.format(
                    "<div style='margin-bottom: 12px;'><strong>Uptime:</strong> %s</div>" +
                    "<div style='margin-bottom: 12px;'><strong>Lattice Node:</strong> <span style='font-family: monospace; color: var(--accent);'>%s</span></div>" +
                    "<div style='margin-bottom: 12px;'><strong>OS:</strong> %s</div>" +
                    "<div><strong>Hosted Plugins:</strong> %d</div>",
                    uptime, context.getNodeId(), osName, pluginsCount
                );
                res.sendHtml(html);
            });

            // Endpoint: Resource Usage (HTML Fragment)
            routes.get("/api/resources", (req, res) -> {
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                long usedMb = heap.getUsed() >> 20;
                long maxMb = heap.getMax() >> 20;
                int memPct = maxMb > 0 ? (int) (heap.getUsed() * 100L / heap.getMax()) : 0;

                OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
                double cpuLoad = os.getSystemLoadAverage();
                // Simple normalize to percentage for visualization purposes (capped at 100%)
                int cpuPct = (int) Math.min(100, Math.max(0, cpuLoad * 100 / os.getAvailableProcessors()));

                String html = String.format(
                    "<div style='margin-bottom: 20px;'>" +
                    "  <div style='display: flex; justify-content: space-between; font-size: 13px; color: var(--text-muted);'>" +
                    "    <span>Heap Memory Usage</span>" +
                    "    <span>%d / %d MB (%d%%)</span>" +
                    "  </div>" +
                    "  <div class='progress-track'>" +
                    "    <div class='progress-fill' style='width: %d%%; background: var(--accent);'></div>" +
                    "  </div>" +
                    "</div>" +
                    "<div>" +
                    "  <div style='display: flex; justify-content: space-between; font-size: 13px; color: var(--text-muted);'>" +
                    "    <span>System CPU Load</span>" +
                    "    <span>%d%%</span>" +
                    "  </div>" +
                    "  <div class='progress-track'>" +
                    "    <div class='progress-fill' style='width: %d%%; background: var(--green);'></div>" +
                    "  </div>" +
                    "</div>",
                    usedMb, maxMb, memPct, memPct, cpuPct, cpuPct
                );
                res.sendHtml(html);
            });

            // Endpoint: Lattice & Services Health
            routes.get("/api/health", (req, res) -> {
                // Get early registered checks or dynamically map status
                StringBuilder sb = new StringBuilder("<div style='display: flex; flex-direction: column; gap: 12px;'>");
                
                sb.append("<div style='display: flex; justify-content: space-between; align-items: center;'>")
                  .append("<span style='font-size: 14px;'>Core Runtime</span>")
                  .append("<span class='status-pill status-running'>UP</span>")
                  .append("</div>");

                sb.append("<div style='display: flex; justify-content: space-between; align-items: center;'>")
                  .append("<span style='font-size: 14px;'>Lattice discovery</span>")
                  .append("<span class='status-pill status-running'>UP</span>")
                  .append("</div>");

                sb.append("</div>");
                res.sendHtml(sb.toString());
            });

            // Endpoint: Container Listings (HTML Fragment)
            routes.get("/api/containers", (req, res) -> {
                String filter = req.getQueryParam("engine", "RAW");
                
                // Get all distrobox and toolbox container IDs for intelligent separation
                List<String> overlayIds = managers.stream()
                    .filter(m -> m.getEngineType() == VContainerEngine.DISTROBOX
                               || m.getEngineType() == VContainerEngine.TOOLBOX)
                    .flatMap(m -> m.listContainers().stream())
                    .map(VContainer::id)
                    .collect(Collectors.toList());

                List<VContainer> containerList = new ArrayList<>();
                for (ContainerManager m : managers) {
                    if ("DISTROBOX".equalsIgnoreCase(filter) && m.getEngineType() == VContainerEngine.DISTROBOX) {
                        containerList.addAll(m.listContainers());
                    } else if ("TOOLBOX".equalsIgnoreCase(filter) && m.getEngineType() == VContainerEngine.TOOLBOX) {
                        containerList.addAll(m.listContainers());
                    } else if ("RAW".equalsIgnoreCase(filter) && m.getEngineType() != VContainerEngine.DISTROBOX
                            && m.getEngineType() != VContainerEngine.TOOLBOX) {
                        containerList.addAll(m.listContainers());
                    }
                }

                if (containerList.isEmpty()) {
                    res.sendHtml("<div style='color: var(--text-muted); font-size: 13px;'>No active containers found.</div>");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("<table>")
                  .append("<thead><tr>")
                  .append("<th>Name</th>")
                  .append("<th>Engine</th>")
                  .append("<th>Image</th>")
                  .append("<th>Status</th>")
                  .append("<th>Actions</th>")
                  .append("</tr></thead>")
                  .append("<tbody>");

                for (VContainer c : containerList) {
                    boolean isOverlayManaged = overlayIds.contains(c.id())
                        && c.engine() != VContainerEngine.DISTROBOX
                        && c.engine() != VContainerEngine.TOOLBOX;
                    boolean isToolboxMgr = c.engine() == VContainerEngine.TOOLBOX;
                    String statusClass = c.isRunning() ? "status-running" : "status-stopped";
                    String statusLabel = c.isRunning() ? "RUNNING" : "STOPPED";
                    String ename = c.engine().name();
                    
                    sb.append("<tr>")
                      .append("<td><strong style='color: var(--text-main);'>").append(sanitizeHtml(c.name())).append("</strong></td>")
                      .append("<td><span style='font-size: 12px; color: var(--text-muted);'>").append(ename).append("</span></td>")
                      .append("<td style='font-family: var(--font-mono); font-size: 12px; color: var(--text-muted);'>").append(sanitizeHtml(c.image())).append("</td>")
                      .append("<td><span class='status-pill ").append(statusClass).append("'>").append(statusLabel).append("</span></td>")
                      .append("<td>");

                    if (isOverlayManaged) {
                        sb.append("<span style='font-size: 11px; color: var(--accent); font-weight: 500; opacity: 0.8;'>Managed by Distrobox/Toolbox</span>");
                    } else if (isToolboxMgr) {
                        sb.append("<span style='font-size: 11px; color: var(--accent); font-weight: 500; opacity: 0.8;'>Toolbox</span>");
                    } else {
                        String safeEngine = sanitizeHtml(ename);
                        String safeId = sanitizeHtml(c.id());
                        String playBtn = c.isRunning()
                            ? String.format("<button class='btn-icon' hx-post='%s/api/action/%s/%s/stop' hx-swap='none' title='Stop'>&#9632;</button>", "/vatn/containers", safeEngine, safeId)
                            : String.format("<button class='btn-icon' hx-post='%s/api/action/%s/%s/start' hx-swap='none' title='Start'>&#9654;</button>", "/vatn/containers", safeEngine, safeId);
                        
                        sb.append(playBtn);
                        sb.append(" <button class='btn-icon' onclick='openTerminal(\"").append(Sanitizer.sanitizeJs(ename))
                          .append("\", \"").append(Sanitizer.sanitizeJs(c.id()))
                          .append("\", \"").append(Sanitizer.sanitizeJs(c.name()))
                          .append("\")' title='Console'>&#9000;</button>");
                    }

                    sb.append("</td>")
                      .append("</tr>");
                }

                sb.append("</tbody></table>");
                res.sendHtml(sb.toString());
            });

            // Endpoint: Action Start Container
            routes.post("/api/action/{engine}/{id}/start", (req, res) -> {
                String engine = req.getPathParam("engine");
                String id = req.getPathParam("id");
                managers.stream()
                    .filter(m -> m.getEngineType().name().equalsIgnoreCase(engine))
                    .findFirst()
                    .ifPresent(m -> m.startContainer(id));
                res.sendEmpty();
            });

            // Endpoint: Action Stop Container
            routes.post("/api/action/{engine}/{id}/stop", (req, res) -> {
                String engine = req.getPathParam("engine");
                String id = req.getPathParam("id");
                managers.stream()
                    .filter(m -> m.getEngineType().name().equalsIgnoreCase(engine))
                    .findFirst()
                    .ifPresent(m -> m.stopContainer(id));
                res.sendEmpty();
            });

            // Endpoint: List Profiles
            routes.get("/api/profiles", (req, res) -> {
                res.send(json.stringify(profileService.list()));
            });

            // Endpoint: Get Profile by ID
            routes.get("/api/profiles/{id}", (req, res) -> {
                var p = profileService.get(req.getPathParam("id"));
                if (p.isEmpty()) { res.status(404).send("{\"error\":\"Not found\"}"); return; }
                res.send(json.stringify(p.get()));
            });

            // Endpoint: Save Profile (create or update)
            routes.post("/api/profiles", (req, res) -> {
                try {
                    var p = json.parse(req.getBody(), ResourceProfile.class);
                    res.send(json.stringify(profileService.save(p)));
                } catch (Exception e) {
                    res.status(400).send("{\"error\":\"" + sanitizeJson(e.getMessage()) + "\"}");
                }
            });

            // Endpoint: Delete Profile
            routes.delete("/api/profiles/{id}", (req, res) -> {
                profileService.delete(req.getPathParam("id"));
                res.sendEmpty();
            });

            // Endpoint: List Stacks
            routes.get("/api/stacks", (req, res) -> {
                res.send(json.stringify(stackService.list()));
            });

            // Endpoint: Get Stack by ID
            routes.get("/api/stacks/{id}", (req, res) -> {
                var s = stackService.get(req.getPathParam("id"));
                if (s.isEmpty()) { res.status(404).send("{\"error\":\"Not found\"}"); return; }
                res.send(json.stringify(s.get()));
            });

            // Endpoint: Save Stack (create or update)
            routes.post("/api/stacks", (req, res) -> {
                try {
                    var s = json.parse(req.getBody(), ContainerStack.class);
                    res.send(json.stringify(stackService.save(s)));
                } catch (Exception e) {
                    res.status(400).send("{\"error\":\"" + sanitizeJson(e.getMessage()) + "\"}");
                }
            });

            // Endpoint: Delete Stack
            routes.delete("/api/stacks/{id}", (req, res) -> {
                stackService.delete(req.getPathParam("id"));
                res.sendEmpty();
            });

            // Endpoint: Deploy Stack
            routes.post("/api/stacks/{id}/deploy", (req, res) -> {
                var s = stackService.get(req.getPathParam("id"));
                if (s.isEmpty()) { res.status(404).send("{\"error\":\"Not found\"}"); return; }
                try {
                    var result = deployer.deploy(s.get());
                    res.send(json.stringify(result));
                } catch (Exception e) {
                    log.error("Stack deploy failed", e);
                    res.status(500).send("{\"error\":\"" + sanitizeJson(e.getMessage()) + "\"}");
                }
            });

            // Endpoint: List Templates
            routes.get("/api/templates", (req, res) -> {
                var list = templateService.list();
                res.send(json.stringify(list));
            });

            // Endpoint: Get Template by ID
            routes.get("/api/templates/{id}", (req, res) -> {
                var t = templateService.get(req.getPathParam("id"));
                if (t.isEmpty()) { res.status(404).send("{\"error\":\"Not found\"}"); return; }
                res.send(json.stringify(t.get()));
            });

            // Endpoint: Save Template (create or update)
            routes.post("/api/templates", (req, res) -> {
                try {
                    var t = json.parse(req.getBody(), ContainerTemplate.class);
                    var saved = templateService.save(t);
                    res.send(json.stringify(saved));
                } catch (Exception e) {
                    res.status(400).send("{\"error\":\"" + sanitizeJson(e.getMessage()) + "\"}");
                }
            });

            // Endpoint: Delete Template
            routes.delete("/api/templates/{id}", (req, res) -> {
                templateService.delete(req.getPathParam("id"));
                res.sendEmpty();
            });

            // Endpoint: Create Container from Template
            routes.post("/api/containers/create", (req, res) -> {
                try {
                    CreateRequest cr = json.parse(req.getBody(), CreateRequest.class);
                    ContainerTemplate template;
                    if (cr.templateId() != null && !cr.templateId().isBlank()) {
                        template = templateService.get(cr.templateId()).orElse(null);
                        if (template == null) {
                            res.status(404).send("{\"error\":\"Template not found\"}");
                            return;
                        }
                    } else {
                        template = new ContainerTemplate(
                            null, cr.name(), "", cr.engine(), cr.image(),
                            cr.containerName(), cr.command(), null,
                            cr.ports(), cr.volumes(), cr.env() != null ? cr.env() : Map.of(),
                            Map.of(), null, null, null, null,
                            cr.postStartCommands(), cr.postStartWaitMs(), 0
                        );
                    }
                    var result = creator.createFromTemplate(template);
                    res.send(json.stringify(result));
                } catch (Exception e) {
                    log.error("Container creation failed", e);
                    res.status(500).send("{\"error\":\"" + sanitizeJson(e.getMessage()) + "\"}");
                }
            });

            // Endpoint: Registered HTTP Routes
            routes.get("/api/routes", (req, res) -> {
                List<String> registered = context.getRegisteredRoutes();
                if (registered.isEmpty()) {
                    res.sendHtml("<div style='color: var(--text-muted); font-size: 13px;'>No HTTP routes registered.</div>");
                    return;
                }

                StringBuilder sb = new StringBuilder("<div style='display: flex; flex-wrap: wrap; gap: 8px;'>");
                for (String r : registered) {
                    sb.append("<span style='font-family: var(--font-mono); font-size: 12px; padding: 4px 8px; background: rgba(255, 255, 255, 0.05); border: 1px solid var(--card-border); border-radius: 6px; color: var(--text-muted);'>")
                      .append(r)
                      .append("</span>");
                }
                sb.append("</div>");
                res.sendHtml(sb.toString());
            });

            // Force Refresh trigger (no-op endpoint to kick off syncs)
            routes.get("/api/trigger-refresh", (req, res) -> res.sendEmpty());
        });
    }

    @Override public String id()    { return "containers"; }
    @Override public String title() { return "Containers"; }
    @Override public String path()  { return "/vatn/containers"; }
    @Override public String icon()  { return "⊞"; }

    @Override
    public void onShutdown() {
        log.info("Shutting down Containers Management Plugin.");
    }

    private static String sanitizeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public record CreateRequest(
        String templateId,
        String name,
        String engine,
        String image,
        String containerName,
        String command,
        List<String> ports,
        List<String> volumes,
        Map<String, String> env,
        List<String> postStartCommands,
        int postStartWaitMs
    ) {}
}
