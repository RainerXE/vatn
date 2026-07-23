package dev.vatn.plugins.containers;

import dev.vatn.api.VProcessService;
import dev.vatn.api.security.VTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ContainerCreator {
    private static final Logger log = LoggerFactory.getLogger(ContainerCreator.class);

    private final VProcessService processService;
    private final List<ContainerManager> managers;
    private final ResourceProfileService profileService;

    public ContainerCreator(VProcessService processService, List<ContainerManager> managers, ResourceProfileService profileService) {
        this.processService = processService;
        this.managers = managers;
        this.profileService = profileService;
    }

    public CreateResult createFromTemplate(ContainerTemplate template) {
        String image = template.image();
        if (image == null || image.isBlank()) {
            return new CreateResult(null, "Image is required", List.of());
        }

        String engineName = template.engine();
        if (engineName == null || engineName.isBlank()) {
            engineName = detectEngine();
        }
        if (engineName == null) {
            return new CreateResult(null, "No container engine available", List.of());
        }

        String containerId = null;
        List<ExecResult> postResults = new ArrayList<>();

        try {
            switch (engineName.toUpperCase()) {
                case "DOCKER" -> containerId = createDocker(template);
                case "PODMAN" -> containerId = createPodman(template);
                case "DISTROBOX" -> containerId = createDistrobox(template);
                case "TOOLBOX" -> containerId = createToolbox(template);
                default -> {
                    return new CreateResult(null, "Unknown engine: " + engineName, List.of());
                }
            }

            if (containerId == null) {
                return new CreateResult(null, "Failed to create container", List.of());
            }

            if (!template.postStartCommands().isEmpty()) {
                if (template.postStartWaitMs() > 0) {
                    try { Thread.sleep(template.postStartWaitMs()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                for (String cmd : template.postStartCommands()) {
                    if (cmd == null || cmd.isBlank()) continue;
                    var result = execInContainer(engineName, containerId, cmd);
                    postResults.add(result);
                }
            }

            return new CreateResult(containerId, null, postResults);
        } catch (Exception e) {
            log.error("Container creation failed", e);
            return new CreateResult(containerId, e.getMessage(), postResults);
        }
    }

    private String detectEngine() {
        for (var mgr : managers) {
            var engine = mgr.getEngineType();
            if (engine == VContainerEngine.TOOLBOX || engine == VContainerEngine.DISTROBOX) continue;
            String binary = engine.name().toLowerCase();
            try {
                var probe = processService.probe(List.of(binary, "--version"));
                if (probe.exitCode() == 0) return engine.name();
            } catch (IOException e) {
                // not available
            }
        }
        return null;
    }

    private String createDocker(ContainerTemplate t) throws IOException {
        var args = buildPodmanArgs("docker", t);
        var result = processService.execute(args, Map.of(), null, VTrustLevel.FULL);
        if (result.exitCode() != 0) throw new RuntimeException("docker create failed: " + result.stderr());
        return result.stdout().trim();
    }

    private String createPodman(ContainerTemplate t) throws IOException {
        var args = buildPodmanArgs("podman", t);
        var result = processService.execute(args, Map.of(), null, VTrustLevel.FULL);
        if (result.exitCode() != 0) throw new RuntimeException("podman create failed: " + result.stderr());
        return result.stdout().trim();
    }

    private List<String> buildPodmanArgs(String binary, ContainerTemplate t) {
        var args = new ArrayList<String>();
        args.add(binary);
        args.add("create");
        if (t.containerName() != null && !t.containerName().isBlank()) {
            args.add("--name");
            args.add(t.containerName());
        }
        for (var p : t.ports()) {
            args.add("-p"); args.add(p);
        }
        for (var v : t.volumes()) {
            args.add("-v"); args.add(v);
        }
        for (var e : t.env().entrySet()) {
            args.add("-e"); args.add(e.getKey() + "=" + e.getValue());
        }
        for (var l : t.labels().entrySet()) {
            args.add("-l"); args.add(l.getKey() + "=" + l.getValue());
        }
        if (t.networkMode() != null && !t.networkMode().isBlank()) {
            args.add("--network"); args.add(t.networkMode());
        }
        if (t.restartPolicy() != null && !t.restartPolicy().isBlank()) {
            args.add("--restart"); args.add(t.restartPolicy());
        }
        if (t.workDir() != null && !t.workDir().isBlank()) {
            args.add("--workdir"); args.add(t.workDir());
        }
        if (t.entrypoint() != null && !t.entrypoint().isBlank()) {
            args.add("--entrypoint"); args.add(t.entrypoint());
        }
        applyResourceArgs(args, t);
        args.add(t.image());
        if (t.command() != null && !t.command().isBlank()) {
            args.addAll(splitCommand(t.command()));
        }
        return args;
    }

    private String createDistrobox(ContainerTemplate t) throws IOException {
        var args = new ArrayList<String>();
        args.add("distrobox"); args.add("create");
        args.add("--image"); args.add(t.image());
        if (t.containerName() != null && !t.containerName().isBlank()) {
            args.add("--name"); args.add(t.containerName());
        }
        var result = processService.execute(args, Map.of(), null, VTrustLevel.FULL);
        if (result.exitCode() != 0) throw new RuntimeException("distrobox create failed: " + result.stderr());
        parseDistroboxResult(result.stdout());
        return t.containerName() != null && !t.containerName().isBlank() ? t.containerName() : t.image();
    }

    private String createToolbox(ContainerTemplate t) throws IOException {
        var args = new ArrayList<String>();
        args.add("toolbox"); args.add("create");
        args.add("--image"); args.add(t.image());
        if (t.containerName() != null && !t.containerName().isBlank()) {
            args.add("--container"); args.add(t.containerName());
        }
        var result = processService.execute(args, Map.of(), null, VTrustLevel.FULL);
        if (result.exitCode() != 0) throw new RuntimeException("toolbox create failed: " + result.stderr());
        return t.containerName() != null && !t.containerName().isBlank() ? t.containerName() : t.image();
    }

    private ExecResult execInContainer(String engine, String containerId, String command) {
        try {
            List<String> args;
            switch (engine.toUpperCase()) {
                case "DOCKER" -> args = List.of("docker", "exec", containerId, "sh", "-c", command);
                case "PODMAN" -> args = List.of("podman", "exec", containerId, "sh", "-c", command);
                case "DISTROBOX" -> args = List.of("distrobox", "enter", containerId, "--", "sh", "-c", command);
                case "TOOLBOX" -> args = List.of("toolbox", "run", "--container", containerId, "sh", "-c", command);
                default -> {
                    return new ExecResult(command, -1, "", "Unknown engine: " + engine);
                }
            }
            var result = processService.execute(args, Map.of(), null, VTrustLevel.FULL);
            return new ExecResult(command, result.exitCode(), result.stdout(), result.stderr());
        } catch (IOException e) {
            return new ExecResult(command, -1, "", e.getMessage());
        }
    }

    private static void parseDistroboxResult(String stdout) {
        // distrobox create prints success message; no container ID to parse
    }

    private void applyResourceArgs(List<String> args, ContainerTemplate t) {
        String profileId = t.resourceProfileId();
        if (profileId == null || profileId.isBlank()) return;

        var profile = profileService.get(profileId);
        if (profile.isEmpty()) {
            log.warn("Resource profile {} not found; skipping resource args", profileId);
            return;
        }
        var p = profile.get();

        if (p.extraCliArgs() != null && !p.extraCliArgs().isBlank()) {
            args.addAll(splitCommand(p.extraCliArgs()));
            return;
        }

        if (p.cpuMax() != null && !p.cpuMax().isBlank()) {
            args.add("--cpus"); args.add(p.cpuMax());
        }
        if (p.memoryMax() != null && !p.memoryMax().isBlank()) {
            args.add("--memory"); args.add(p.memoryMax());
        }
        for (var d : p.deviceMounts()) {
            if (d != null && !d.isBlank()) {
                args.add("--device"); args.add(d);
            }
        }
        if (p.gpuMode() != null && !p.gpuMode().isBlank() && !"none".equals(p.gpuMode())) {
            if ("all".equals(p.gpuMode())) {
                args.add("--gpus"); args.add("all");
            } else if (p.gpuMode().startsWith("count:")) {
                args.add("--gpus"); args.add(p.gpuMode());
            } else {
                args.add("--gpus"); args.add("device=" + p.gpuMode());
            }
        }
    }

    private static List<String> splitCommand(String cmd) {
        var parts = new ArrayList<String>();
        boolean inQuote = false;
        var buf = new StringBuilder();
        for (char c : cmd.toCharArray()) {
            if (c == '"' || c == '\'') { inQuote = !inQuote; continue; }
            if (c == ' ' && !inQuote) {
                if (!buf.isEmpty()) { parts.add(buf.toString()); buf.setLength(0); }
            } else {
                buf.append(c);
            }
        }
        if (!buf.isEmpty()) parts.add(buf.toString());
        return parts;
    }

    public record CreateResult(String containerId, String error, List<ExecResult> postStartResults) {}
    public record ExecResult(String command, int exitCode, String stdout, String stderr) {}
}
