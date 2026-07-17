package dev.vatn.plugins.python;

import dev.vatn.api.VService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all running (or recently stopped) Python processes.
 * Registered as a {@link VService} in the VATN context.
 */
public class PythonProcessManager implements VService {

    private static final Logger log = LoggerFactory.getLogger(PythonProcessManager.class);

    private final Map<String, PythonProcess> processes = new ConcurrentHashMap<>();
    private final PythonConfig config;

    public PythonProcessManager(PythonConfig config) {
        this.config = config;
    }

    public PythonProcess register(String id, String appId, List<String> command,
                                  Map<String, String> env, java.nio.file.Path workDir,
                                  boolean autoRestart) {
        var proc = new PythonProcess(id, appId, command, env, workDir,
            autoRestart, config.restartDelayMs(), config.maxLogLines());
        processes.put(id, proc);
        return proc;
    }

    public Optional<PythonProcess> get(String id) {
        return Optional.ofNullable(processes.get(id));
    }

    public Collection<PythonProcess> getAll() {
        return Collections.unmodifiableCollection(processes.values());
    }

    public Collection<PythonProcess> getByApp(String appId) {
        return processes.values().stream()
            .filter(p -> appId.equals(p.appId()))
            .toList();
    }

    public void stopAll() {
        processes.values().forEach(p -> {
            if (p.status() == PythonProcess.Status.RUNNING) {
                log.info("[PYTHON] Stopping process {}", p.id());
                p.stop();
            }
        });
    }

    public void remove(String id) {
        PythonProcess p = processes.remove(id);
        if (p != null && p.status() == PythonProcess.Status.RUNNING) p.stop();
    }
}
