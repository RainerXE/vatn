package dev.vatn.plugins.containers;

import dev.vatn.api.VProcessService;
import dev.vatn.api.security.VTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DistroboxManager implements ContainerManager {
    private static final Logger log = LoggerFactory.getLogger(DistroboxManager.class);

    private final VProcessService processService;

    public DistroboxManager(VProcessService processService) {
        this.processService = processService;
    }

    @Override
    public VContainerEngine getEngineType() {
        return VContainerEngine.DISTROBOX;
    }

    @Override
    public List<VContainer> listContainers() {
        List<VContainer> containers = new ArrayList<>();
        try {
            VProcessService.VProcessResult check = processService.probe(List.of("distrobox", "--version"));
            if (check.exitCode() != 0) {
                return containers;
            }

            VProcessService.VProcessResult result = processService.execute(
                List.of("distrobox", "list", "--no-color"),
                Map.of(),
                null,
                VTrustLevel.FULL
            );

            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                String[] lines = result.stdout().split("\n");
                // Expect format: ID | NAME | STATUS | IMAGE
                for (int i = 1; i < lines.length; i++) { // Skip header
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    
                    String[] parts = line.split("\\|");
                    if (parts.length >= 4) {
                        String id = parts[0].trim();
                        String name = parts[1].trim();
                        String status = parts[2].trim();
                        String image = parts[3].trim();
                        boolean isRunning = status.toLowerCase().contains("up") || status.toLowerCase().contains("running");

                        containers.add(new VContainer(id, name, VContainerEngine.DISTROBOX, image, status, isRunning, Map.of()));
                    }
                }
            }
        } catch (IOException e) {
            log.debug("distrobox not available or failed: {}", e.getMessage());
        }
        return containers;
    }

    @Override
    public void startContainer(String id) {
        try {
            // distrobox start is non-interactive if --yes is passed or if just started in background
            processService.execute(List.of("distrobox", "start", id), Map.of(), null, VTrustLevel.FULL);
        } catch (IOException e) {
            log.error("Failed to start distrobox container {}: {}", id, e.getMessage());
        }
    }

    @Override
    public void stopContainer(String id) {
        try {
            processService.execute(List.of("distrobox", "stop", "--yes", id), Map.of(), null, VTrustLevel.FULL);
        } catch (IOException e) {
            log.error("Failed to stop distrobox container {}: {}", id, e.getMessage());
        }
    }

    @Override
    public VProcessService.VProcessHandle executeInteractive(String id, List<String> command, VTrustLevel trustLevel) throws IOException {
        List<String> execCmd = new ArrayList<>();
        execCmd.add("distrobox");
        execCmd.add("enter");
        execCmd.add(id);
        execCmd.add("--");
        execCmd.addAll(command);

        return processService.startAsync(execCmd, Map.of(), null, trustLevel);
    }
}
