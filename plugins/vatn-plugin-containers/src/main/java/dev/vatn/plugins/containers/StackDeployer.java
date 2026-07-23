package dev.vatn.plugins.containers;

import dev.vatn.api.VProcessService;
import dev.vatn.api.security.VTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

public class StackDeployer {
    private static final Logger log = LoggerFactory.getLogger(StackDeployer.class);

    private final ContainerCreator containerCreator;
    private final List<ContainerManager> managers;
    private final VProcessService processService;
    private final TemplateService templateService;

    public StackDeployer(ContainerCreator containerCreator, List<ContainerManager> managers,
                         VProcessService processService, TemplateService templateService) {
        this.containerCreator = containerCreator;
        this.managers = managers;
        this.processService = processService;
        this.templateService = templateService;
    }

    public DeploymentRun deploy(ContainerStack stack) {
        List<DeploymentRun.ServiceResult> results = new ArrayList<>();

        // 1. Validate: check all template refs exist
        for (var svc : stack.services()) {
            if (svc.templateId() != null && !svc.templateId().isBlank()) {
                if (templateService.get(svc.templateId()).isEmpty()) {
                    results.add(new DeploymentRun.ServiceResult(
                        svc.name(), "FAILED", null,
                        "Template not found: " + svc.templateId()));
                }
            }
        }
        if (!results.isEmpty()) {
            return new DeploymentRun(stack.id(), "FAILED", results);
        }

        // 2. Topological sort
        List<StackService> sorted;
        try {
            sorted = topologicalSort(stack.services());
        } catch (IllegalArgumentException e) {
            return new DeploymentRun(stack.id(), "FAILED", List.of(
                new DeploymentRun.ServiceResult("", "FAILED", null, e.getMessage())));
        }

        // 3. Deploy in order
        Map<String, DeploymentRun.ServiceResult> deployed = new LinkedHashMap<>();
        // Initialize all as WAITING
        for (var svc : stack.services()) {
            deployed.put(svc.name(), new DeploymentRun.ServiceResult(svc.name(), "WAITING", null, null));
        }

        for (var svc : sorted) {
            try {
                // Wait for dependencies
                boolean depsMet = waitForDependencies(svc, deployed);
                if (!depsMet) {
                    deployed.put(svc.name(), new DeploymentRun.ServiceResult(
                        svc.name(), "SKIPPED", null, "Dependency not met"));
                    continue;
                }

                // Resolve template
                var templateOpt = templateService.get(svc.templateId());
                if (templateOpt.isEmpty()) {
                    deployed.put(svc.name(), new DeploymentRun.ServiceResult(
                        svc.name(), "FAILED", null, "Template not found"));
                    continue;
                }
                var template = templateOpt.get();

                // Apply env overrides
                ContainerTemplate effectiveTemplate;
                if (svc.env() != null && !svc.env().isEmpty()) {
                    var mergedEnv = new HashMap<>(template.env());
                    mergedEnv.putAll(svc.env());
                    effectiveTemplate = new ContainerTemplate(
                        template.id(), template.name(), template.description(),
                        template.engine(), template.image(), template.containerName(),
                        template.command(), template.entrypoint(),
                        template.ports(), template.volumes(), mergedEnv,
                        template.labels(), template.networkMode(),
                        template.restartPolicy(), template.workDir(),
                        template.resourceProfileId(),
                        template.postStartCommands(), template.postStartWaitMs(),
                        template.createdAt());
                } else {
                    effectiveTemplate = template;
                }

                // Create container
                var createResult = containerCreator.createFromTemplate(effectiveTemplate);
                if (createResult.error() != null) {
                    deployed.put(svc.name(), new DeploymentRun.ServiceResult(
                        svc.name(), "FAILED", null, createResult.error()));
                    continue;
                }

                String containerId = createResult.containerId();

                // Start container
                boolean started = startContainer(containerId);
                if (!started) {
                    deployed.put(svc.name(), new DeploymentRun.ServiceResult(
                        svc.name(), "FAILED", containerId, "Failed to start container"));
                    continue;
                }

                // Run health probe if configured
                if (svc.healthProbe() != null) {
                    boolean healthy = runHealthProbe(svc.healthProbe(), containerId);
                    if (!healthy) {
                        deployed.put(svc.name(), new DeploymentRun.ServiceResult(
                            svc.name(), "UNHEALTHY", containerId, "Health probe failed"));
                        continue;
                    }
                }

                deployed.put(svc.name(), new DeploymentRun.ServiceResult(
                    svc.name(), "RUNNING", containerId, null));
            } catch (Exception e) {
                log.error("Deploy failed for service {}", svc.name(), e);
                deployed.put(svc.name(), new DeploymentRun.ServiceResult(
                    svc.name(), "FAILED", null, e.getMessage()));
            }
        }

        // Build result list in original order
        List<DeploymentRun.ServiceResult> orderedResults = new ArrayList<>();
        for (var svc : stack.services()) {
            orderedResults.add(deployed.get(svc.name()));
        }

        String overall = orderedResults.stream().allMatch(r -> "RUNNING".equals(r.status())) ? "RUNNING" : "DEGRADED";
        return new DeploymentRun(stack.id(), overall, orderedResults);
    }

    private boolean waitForDependencies(StackService svc, Map<String, DeploymentRun.ServiceResult> deployed) {
        if (svc.dependsOn() == null || svc.dependsOn().isEmpty()) return true;
        for (var dep : svc.dependsOn()) {
            var depResult = deployed.get(dep.service());
            if (depResult == null) return false;
            if ("SKIPPED".equals(depResult.status()) || "FAILED".equals(depResult.status())) return false;
            if ("healthy".equals(dep.condition()) && !"RUNNING".equals(depResult.status())) return false;
        }
        return true;
    }

    private boolean startContainer(String containerId) {
        for (var mgr : managers) {
            try {
                mgr.startContainer(containerId);
                return true;
            } catch (Exception e) {
                // try next manager
            }
        }
        return false;
    }

    private boolean runHealthProbe(HealthProbe probe, String containerId) {
        long deadline = System.currentTimeMillis() + (long) probe.retries() * probe.intervalMs();
        int attempt = 0;
        // Wait for start period
        if (probe.startPeriodMs() > 0) {
            try { Thread.sleep(probe.startPeriodMs()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        while (System.currentTimeMillis() < deadline && attempt < probe.retries()) {
            try {
                boolean ok = switch (probe.type()) {
                    case "exec" -> checkExec(probe, containerId);
                    case "tcp" -> checkTcp(probe);
                    case "http" -> checkHttp(probe);
                    default -> false;
                };
                if (ok) return true;
            } catch (Exception e) {
                log.debug("Health probe attempt {} failed: {}", attempt, e.getMessage());
            }
            attempt++;
            if (attempt < probe.retries()) {
                try { Thread.sleep(probe.intervalMs()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private boolean checkExec(HealthProbe probe, String containerId) throws IOException {
        var result = processService.execute(
            List.of("podman", "exec", containerId, "sh", "-c", probe.value()),
            Map.of(), null, VTrustLevel.FULL);
        return result.exitCode() == 0;
    }

    private boolean checkTcp(HealthProbe probe) {
        try (var socket = new Socket()) {
            String[] parts = probe.value().split(":");
            String host = parts.length > 1 ? parts[0] : "localhost";
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : Integer.parseInt(parts[0]);
            socket.connect(new InetSocketAddress(host, port), probe.timeoutMs());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkHttp(HealthProbe probe) {
        // Simple HTTP health check via ProcessService using curl
        try {
            var result = processService.execute(
                List.of("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--connect-timeout",
                    String.valueOf(probe.timeoutMs() / 1000), probe.value()),
                Map.of(), null, VTrustLevel.FULL);
            return result.exitCode() == 0 && result.stdout().startsWith("2");
        } catch (IOException e) {
            return false;
        }
    }

    // Topological sort using Kahn's algorithm
    static List<StackService> topologicalSort(List<StackService> services) {
        Map<String, StackService> byName = new LinkedHashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (var svc : services) {
            byName.put(svc.name(), svc);
            adjacency.putIfAbsent(svc.name(), new ArrayList<>());
            inDegree.putIfAbsent(svc.name(), 0);
        }

        for (var svc : services) {
            if (svc.dependsOn() != null) {
                for (var dep : svc.dependsOn()) {
                    if (!byName.containsKey(dep.service())) {
                        throw new IllegalArgumentException("Dependency '" + dep.service()
                            + "' for service '" + svc.name() + "' not found in stack");
                    }
                    adjacency.computeIfAbsent(dep.service(), k -> new ArrayList<>()).add(svc.name());
                    inDegree.merge(svc.name(), 1, Integer::sum);
                }
            }
        }

        // Detect cycles: if inDegree has any node with no path to a root
        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            sorted.add(node);
            for (var neighbor : adjacency.getOrDefault(node, List.of())) {
                inDegree.merge(neighbor, -1, Integer::sum);
                if (inDegree.get(neighbor) == 0) queue.add(neighbor);
            }
        }

        if (sorted.size() != services.size()) {
            throw new IllegalArgumentException("Cycle detected in service dependencies");
        }

        List<StackService> result = new ArrayList<>();
        for (String name : sorted) {
            result.add(byName.get(name));
        }
        return result;
    }
}
