package dev.vatn.core.supervisor;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VPluginDescriptor;
import dev.vatn.api.VWatchdogService;
import dev.vatn.core.plugin.OipcProcessPluginProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the system daemon supervisor for VATN. Physically spans ProcessBuilders
 * for OUT_OF_PROCESS_BIN plugins, finds ephemeral ports, and monitors process health.
 */
public class VWatchdogServiceImpl implements VWatchdogService {

    private static final Logger log = LoggerFactory.getLogger(VWatchdogServiceImpl.class);

    private VNodeContext context;
    private final Map<String, Process> trackedProcesses = new ConcurrentHashMap<>();
    private final Map<String, OipcProcessPluginProxy> proxies = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private volatile boolean failOnLimits = false;
    private Thread healthPoller;

    private static final long HEARTBEAT_TIMEOUT_MS = 10000;
    private static final long MEMORY_CHECK_INTERVAL_MS = 5000;
    private static final long SOFT_MEMORY_LIMIT_MB = 512;
    private static final long HARD_MEMORY_LIMIT_MB = 2048; // 4x Soft Limit

    public VWatchdogServiceImpl(VNodeContext context) {
        this.context = context;
    }

    public void setFailOnLimits(boolean failOnLimits) {
        this.failOnLimits = failOnLimits;
    }

    @Override
    public void supervise(String pluginId, VPluginDescriptor descriptor) {
        log.info("Watchdog beginning supervision for plugin {}", pluginId);

        // Determine execution mode from manifest
        String command = null;
        if (descriptor.getManifest() != null && descriptor.getManifest().getExecution() != null) {
            if ("OUT_OF_PROCESS_BIN".equals(descriptor.getManifest().getExecution().getMode())) {
                command = descriptor.getManifest().getExecution().getEntrypoint();
            }
        }

        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Plugin " + pluginId + " does not define an OUT_OF_PROCESS_BIN entrypoint in manifest.");
        }

        try {
            // Find a free ephemeral local port for the IPC socket
            int ipcPort = findFreePort();
            
            // Spawn the external binary
            ProcessBuilder pb = new ProcessBuilder(splitCommand(command));
            pb.directory(descriptor.getSourcePath().getParent().toFile()); // RUN IN PLUGIN DIR
            pb.environment().put("VATN_IPC_PORT", String.valueOf(ipcPort));
            // Instead of inheritIO, we capture for the log aggregator
            pb.redirectErrorStream(true); 
            
            log.info("Watchdog spawning external process: {} with IPC port {}", command, ipcPort);
            Process process = pb.start();
            trackedProcesses.put(pluginId, process);

            // Spawn a thread to pipe output to the master node's log with markings
            Thread.ofVirtual().start(() -> captureOutput(pluginId, process));

            // Give it 1 second to bind to the socket natively before we connect.
            Thread.sleep(1000);

            // Establish the VATN Proxy hook
            OipcProcessPluginProxy proxy = new OipcProcessPluginProxy(descriptor, ipcPort);
            proxy.setHeartbeatCallback(() -> lastHeartbeat.put(pluginId, System.currentTimeMillis()));
            proxy.onInitialize(context);
            proxies.put(pluginId, proxy);
            lastHeartbeat.put(pluginId, System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Watchdog failed to spin up {}", pluginId, e);
            throw new RuntimeException("Failed to supervise external plugin", e);
        }
    }

    @Override
    public void drop(String pluginId) {
        OipcProcessPluginProxy proxy = proxies.remove(pluginId);
        if (proxy != null) proxy.onShutdown();
        
        Process process = trackedProcesses.remove(pluginId);
        if (process != null && process.isAlive()) {
            log.info("Watchdog dropping process for {}", pluginId);
            process.destroy();
        }
    }

    @Override
    public void start() {
        running = true;
        healthPoller = new Thread(this::pollLoop, "vatn-watchdog-poller");
        healthPoller.setDaemon(true);
        healthPoller.start();
    }

    public boolean isHealthy(String pluginId) {
        Process p = trackedProcesses.get(pluginId);
        return p != null && p.isAlive();
    }

    private void pollLoop() {
        while (running) {
            try {
                Thread.sleep(MEMORY_CHECK_INTERVAL_MS);
                long now = System.currentTimeMillis();

                for (Map.Entry<String, Process> entry : trackedProcesses.entrySet()) {
                    String pluginId = entry.getKey();
                    Process process = entry.getValue();

                    if (!process.isAlive()) {
                        log.warn("Watchdog detected CRASH for plugin {}.", pluginId);
                        continue;
                    }

                    // 1. Memory Monitoring (RSS)
                    long rssBytes = getProcessRSS(process.pid());
                    if (rssBytes > 0) {
                        checkMemoryLimits(pluginId, rssBytes);
                    }

                    // 2. Heartbeat Monitoring (Ping/Pong)
                    if (proxies.containsKey(pluginId)) {
                        proxies.get(pluginId).ping();
                    }
                    checkHeartbeat(pluginId, now);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (healthPoller != null) healthPoller.interrupt();
        for (String id : trackedProcesses.keySet()) {
            drop(id);
        }
    }

    private void checkMemoryLimits(String pluginId, long rssBytes) {
        double mb = rssBytes / 1024.0 / 1024.0;
        
        if (mb > HARD_MEMORY_LIMIT_MB) {
            log.error("[WATCHDOG] FATAL: Hard memory ceiling (4x) exceeded for {}: {} MB", pluginId, String.format("%.2f", mb));
            if (failOnLimits) {
                log.error("[WATCHDOG] Emergency shutdown of {} due to memory ceiling.", pluginId);
                drop(pluginId);
            }
        } else if (mb > SOFT_MEMORY_LIMIT_MB) {
            log.warn("[WATCHDOG] High memory warning for {}: {} MB", pluginId, String.format("%.2f", mb));
        }
    }

    private void checkHeartbeat(String pluginId, long now) {
        OipcProcessPluginProxy proxy = proxies.get(pluginId);
        if (proxy == null) return;

        if (now - lastHeartbeat.getOrDefault(pluginId, 0L) > HEARTBEAT_TIMEOUT_MS) {
            log.error("[WATCHDOG] HANG detected for {}! No heartbeat for {}ms", pluginId, now - lastHeartbeat.get(pluginId));
            if (failOnLimits) {
                log.error("[WATCHDOG] Emergency shutdown of {} due to execution hang.", pluginId);
                drop(pluginId);
            }
        }
    }

    private long getProcessRSS(long pid) {
        try {
            // macOS/Linux compatible RSS lookup
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return Long.parseLong(line.trim()) * 1024; // ps returns KB
                }
            }
        } catch (Exception e) {
            // Silently fail on non-supported platforms
        }
        return -1;
    }

    private void captureOutput(String pluginId, Process process) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[{}] {}", pluginId, line);
            }
        } catch (IOException e) {
            log.error("[WATCHDOG] Outpipe capture failed for {}", pluginId, e);
        }
        if (process.isAlive()) {
            log.warn("[WATCHDOG] Output stream closed for {} while process is still alive — process may be hung", pluginId);
        } else {
            log.debug("[WATCHDOG] Output stream closed for {} — process exited with code {}", pluginId, process.exitValue());
        }
    }

    private static List<String> splitCommand(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) { quote = 0; }
                else { current.append(c); }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ' ') {
                if (current.length() > 0) { tokens.add(current.toString()); current.setLength(0); }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
