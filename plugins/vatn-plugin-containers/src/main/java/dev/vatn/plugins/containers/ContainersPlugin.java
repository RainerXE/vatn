package dev.vatn.plugins.containers;

import dev.vatn.api.*;
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

public class ContainersPlugin implements VNodePlugin {
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
        return "1.0-alpha.14";
    }

    @Override
    public void onInitialize(VNodeContext context) {
        // 1. Initialize Managers
        VProcessService processService = context.getService(VProcessService.class).orElseThrow();
        VJson json = context.getJson();

        managers = List.of(
            new GenericContainerManager(VContainerEngine.DOCKER, "docker", processService, json),
            new GenericContainerManager(VContainerEngine.PODMAN, "podman", processService, json),
            new DistroboxManager(processService)
        );

        // 2. Register with Workload Registry
        context.getService(VWorkloadRegistry.class).ifPresent(registry -> {
            registry.registerProvider(new ContainersWorkloadProvider(managers));
        });

        // 3. Register WebSocket Route for Web Terminal
        context.registerWebSocket("/vatn/containers/ws/exec", new WebTerminalHandler(managers));

        // 4. Register HTTP Web Dashboard UI
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
                
                String html = String.format(
                    "<div style='margin-bottom: 12px;'><strong>Uptime:</strong> %s</div>" +
                    "<div style='margin-bottom: 12px;'><strong>Lattice Node:</strong> <span style='font-family: monospace; color: var(--accent);'>%s</span></div>" +
                    "<div><strong>Hosted Plugins:</strong> %d</div>",
                    uptime, context.getNodeId(), pluginsCount
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
                
                // Get all distrobox container IDs first for intelligent separation
                List<String> distroboxIds = managers.stream()
                    .filter(m -> m.getEngineType() == VContainerEngine.DISTROBOX)
                    .flatMap(m -> m.listContainers().stream())
                    .map(VContainer::id)
                    .collect(Collectors.toList());

                List<VContainer> containerList = new ArrayList<>();
                for (ContainerManager m : managers) {
                    if ("DISTROBOX".equalsIgnoreCase(filter) && m.getEngineType() == VContainerEngine.DISTROBOX) {
                        containerList.addAll(m.listContainers());
                    } else if ("RAW".equalsIgnoreCase(filter) && m.getEngineType() != VContainerEngine.DISTROBOX) {
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
                    boolean isDistroboxManaged = distroboxIds.contains(c.id()) && c.engine() != VContainerEngine.DISTROBOX;
                    String statusClass = c.isRunning() ? "status-running" : "status-stopped";
                    String statusLabel = c.isRunning() ? "RUNNING" : "STOPPED";
                    
                    sb.append("<tr>")
                      .append("<td><strong style='color: var(--text-main);'>").append(c.name()).append("</strong></td>")
                      .append("<td><span style='font-size: 12px; color: var(--text-muted);'>").append(c.engine().name()).append("</span></td>")
                      .append("<td style='font-family: var(--font-mono); font-size: 12px; color: var(--text-muted);'>").append(c.image()).append("</td>")
                      .append("<td><span class='status-pill ").append(statusClass).append("'>").append(statusLabel).append("</span></td>")
                      .append("<td>");

                    if (isDistroboxManaged) {
                        // Mark clearly as managed by distrobox
                        sb.append("<span style='font-size: 11px; color: var(--accent); font-weight: 500; opacity: 0.8;'>Managed by Distrobox</span>");
                    } else {
                        // Actions
                        String playBtn = c.isRunning()
                            ? String.format("<button class='btn-icon' hx-post='%s/api/action/%s/%s/stop' hx-swap='none' title='Stop'>&#9632;</button>", "/vatn/containers", c.engine().name(), c.id())
                            : String.format("<button class='btn-icon' hx-post='%s/api/action/%s/%s/start' hx-swap='none' title='Start'>&#9654;</button>", "/vatn/containers", c.engine().name(), c.id());
                        
                        sb.append(playBtn);
                        sb.append(String.format(" <button class='btn-icon' onclick='openTerminal(\"%s\", \"%s\", \"%s\")' title='Console'>&#9000;</button>", c.engine().name(), c.id(), c.name()));
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

            // Endpoint: Global Workloads Registry
            routes.get("/api/global-workloads", (req, res) -> {
                Optional<VWorkloadRegistry> registry = context.getService(VWorkloadRegistry.class);
                if (registry.isEmpty()) {
                    res.sendHtml("<div style='color: var(--text-muted); font-size: 13px;'>Workload registry unavailable.</div>");
                    return;
                }

                var workloads = registry.get().getAllWorkloads();
                if (workloads.isEmpty()) {
                    res.sendHtml("<div style='color: var(--text-muted); font-size: 13px;'>No active workloads running on this node.</div>");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("<table>")
                  .append("<thead><tr>")
                  .append("<th>ID</th>")
                  .append("<th>Name</th>")
                  .append("<th>Type</th>")
                  .append("<th>Status</th>")
                  .append("<th>Details</th>")
                  .append("</tr></thead>")
                  .append("<tbody>");

                for (var w : workloads) {
                    String badgeClass = switch (w.type()) {
                        case WASM -> "background: rgba(139, 92, 246, 0.1); color: #a78bfa;";
                        case CONTAINER -> "background: rgba(59, 130, 246, 0.1); color: #60a5fa;";
                        case PROCESS -> "background: rgba(245, 158, 11, 0.1); color: #fbbf24;";
                        case DAG_TASK -> "background: rgba(16, 185, 129, 0.1); color: #34d399;";
                        case NATIVE -> "background: rgba(239, 68, 68, 0.1); color: #f87171;";
                    };

                    sb.append("<tr>")
                      .append("<td style='font-family: var(--font-mono); font-size: 12px; color: var(--text-muted);'>").append(w.id().substring(0, Math.min(8, w.id().length()))).append("</td>")
                      .append("<td><strong style='color: var(--text-main);'>").append(w.name()).append("</strong></td>")
                      .append("<td><span style='font-size: 11px; padding: 2px 8px; border-radius: 4px; font-weight: 500; ").append(badgeClass).append("'>").append(w.type().name()).append("</span></td>")
                      .append("<td><span class='status-pill status-running'>").append(w.status().name()).append("</span></td>")
                      .append("<td style='font-size: 12px; color: var(--text-muted);'>").append(w.resourceUsage().toString()).append("</td>")
                      .append("</tr>");
                }

                sb.append("</tbody></table>");
                res.sendHtml(sb.toString());
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

    @Override
    public void onShutdown() {
        log.info("Shutting down Containers Management Plugin.");
    }
}
