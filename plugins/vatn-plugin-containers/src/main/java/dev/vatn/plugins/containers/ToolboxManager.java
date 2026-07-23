package dev.vatn.plugins.containers;

import dev.vatn.api.VProcessService;
import dev.vatn.api.security.VTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ToolboxManager implements ContainerManager {
    private static final Logger log = LoggerFactory.getLogger(ToolboxManager.class);

    private final VProcessService processService;

    public ToolboxManager(VProcessService processService) {
        this.processService = processService;
    }

    @Override
    public VContainerEngine getEngineType() {
        return VContainerEngine.TOOLBOX;
    }

    @Override
    public List<VContainer> listContainers() {
        List<VContainer> containers = new ArrayList<>();
        try {
            VProcessService.VProcessResult check = processService.probe(List.of("toolbox", "--version"));
            if (check.exitCode() != 0) return containers;

            VProcessService.VProcessResult result = processService.execute(
                List.of("toolbox", "list", "-c"),
                Map.of(), null, VTrustLevel.FULL
            );

            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                String[] lines = result.stdout().split("\n");
                boolean header = true;
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    if (header) {
                        header = false;
                        continue;
                    }
                    String[] parts = line.split("\\s{2,}");
                    if (parts.length >= 5) {
                        String id = parts[0].trim();
                        String name = parts[1].trim();
                        String status = parts[3].trim();
                        String image = parts[4].trim();
                        boolean isRunning = status.equalsIgnoreCase("running")
                            || status.equalsIgnoreCase("Up")
                            || status.toLowerCase().contains("up");
                        containers.add(new VContainer(id, name, VContainerEngine.TOOLBOX,
                            image, status, isRunning, Map.of()));
                    }
                }
            }
        } catch (IOException e) {
            log.debug("toolbox not available or failed: {}", e.getMessage());
        }
        return containers;
    }

    @Override
    public void startContainer(String id) {
        try {
            processService.execute(List.of("podman", "start", id),
                Map.of(), null, VTrustLevel.FULL);
        } catch (IOException e) {
            log.error("Failed to start toolbox container {}: {}", id, e.getMessage());
        }
    }

    @Override
    public void stopContainer(String id) {
        try {
            processService.execute(List.of("podman", "stop", id),
                Map.of(), null, VTrustLevel.FULL);
        } catch (IOException e) {
            log.error("Failed to stop toolbox container {}: {}", id, e.getMessage());
        }
    }

    @Override
    public VProcessService.VProcessHandle executeInteractive(String id, List<String> command, VTrustLevel trustLevel)
            throws IOException {
        List<String> execCmd = new ArrayList<>();
        execCmd.add("toolbox");
        execCmd.add("enter");
        execCmd.add("--container");
        execCmd.add(id);
        execCmd.add("--");
        execCmd.addAll(command);
        return processService.startAsync(execCmd, Map.of(), null, trustLevel);
    }
}
