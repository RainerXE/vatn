package dev.vatn.plugins.containers;

import dev.vatn.api.VJson;
import dev.vatn.api.VProcessService;
import dev.vatn.api.security.VTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GenericContainerManager implements ContainerManager {
    private static final Logger log = LoggerFactory.getLogger(GenericContainerManager.class);

    private final VContainerEngine engine;
    private final String binary;
    private final VProcessService processService;
    private final VJson json;

    public GenericContainerManager(VContainerEngine engine, String binary, VProcessService processService, VJson json) {
        this.engine = engine;
        this.binary = binary;
        this.processService = processService;
        this.json = json;
    }

    @Override
    public VContainerEngine getEngineType() {
        return engine;
    }

    @Override
    public List<VContainer> listContainers() {
        List<VContainer> containers = new ArrayList<>();
        try {
            // Check if binary exists/runs
            VProcessService.VProcessResult check = processService.probe(List.of(binary, "--version"));
            if (check.exitCode() != 0) {
                return containers;
            }

            VProcessService.VProcessResult result = processService.execute(
                List.of(binary, "ps", "-a", "--format", "{{json .}}"),
                Map.of(),
                null,
                VTrustLevel.FULL
            );

            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                String[] lines = result.stdout().split("\n");
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = json.parse(line, Map.class);
                        String id = (String) map.getOrDefault("ID", "");
                        String name = (String) map.getOrDefault("Names", "");
                        String image = (String) map.getOrDefault("Image", "");
                        String state = (String) map.getOrDefault("State", "");
                        String status = (String) map.getOrDefault("Status", "");
                        boolean isRunning = "running".equalsIgnoreCase(state);

                        containers.add(new VContainer(id, name, engine, image, status, isRunning, Map.of()));
                    } catch (Exception e) {
                        log.debug("Failed to parse container line: {}", line, e);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Engine {} not available or failed: {}", engine, e.getMessage());
        }
        return containers;
    }

    @Override
    public void startContainer(String id) {
        try {
            processService.execute(List.of(binary, "start", id), Map.of(), null, VTrustLevel.FULL);
        } catch (IOException e) {
            log.error("Failed to start container {} via {}: {}", id, binary, e.getMessage());
        }
    }

    @Override
    public void stopContainer(String id) {
        try {
            processService.execute(List.of(binary, "stop", id), Map.of(), null, VTrustLevel.FULL);
        } catch (IOException e) {
            log.error("Failed to stop container {} via {}: {}", id, binary, e.getMessage());
        }
    }

    @Override
    public VProcessService.VProcessHandle executeInteractive(String id, List<String> command, VTrustLevel trustLevel) throws IOException {
        List<String> execCmd = new ArrayList<>();
        execCmd.add(binary);
        execCmd.add("exec");
        execCmd.add("-i"); // keep stdin open for interactive usage
        execCmd.add(id);
        execCmd.addAll(command);

        return processService.startAsync(execCmd, Map.of(), null, trustLevel);
    }
}
