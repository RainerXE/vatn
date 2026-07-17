package dev.vatn.plugins.node;

import dev.vatn.api.VService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of all running Node.js processes. Registered as a {@link VService}. */
public class NodeProcessManager implements VService {

    private static final Logger log = LoggerFactory.getLogger(NodeProcessManager.class);

    private final Map<String, NodeProcess> processes = new ConcurrentHashMap<>();
    private final NodeConfig               config;

    public NodeProcessManager(NodeConfig config) { this.config = config; }

    public NodeProcess register(String id, String appId, List<String> command,
                                Map<String, String> env, Path workDir, boolean autoRestart) {
        var proc = new NodeProcess(id, appId, command, env, workDir,
            autoRestart, config.restartDelayMs(), config.maxLogLines());
        processes.put(id, proc);
        return proc;
    }

    public Optional<NodeProcess> get(String id)      { return Optional.ofNullable(processes.get(id)); }
    public Collection<NodeProcess> getAll()          { return Collections.unmodifiableCollection(processes.values()); }
    public Collection<NodeProcess> getByApp(String appId) {
        return processes.values().stream().filter(p -> appId.equals(p.appId())).toList();
    }

    public void stopAll() {
        processes.values().forEach(p -> {
            if (p.status() == NodeProcess.Status.RUNNING) {
                log.info("[NODE] Stopping process {}", p.id());
                p.stop();
            }
        });
    }

    public void remove(String id) {
        NodeProcess p = processes.remove(id);
        if (p != null && p.status() == NodeProcess.Status.RUNNING) p.stop();
    }
}
