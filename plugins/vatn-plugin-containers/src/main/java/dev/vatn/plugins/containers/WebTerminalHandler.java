package dev.vatn.plugins.containers;

import dev.vatn.api.VProcessService;
import dev.vatn.api.VWsListener;
import dev.vatn.api.VWsSession;
import dev.vatn.api.security.VTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebTerminalHandler implements VWsListener {
    private static final Logger log = LoggerFactory.getLogger(WebTerminalHandler.class);

    private final List<ContainerManager> managers;
    private final Map<VWsSession, VProcessService.VProcessHandle> activeSessions = new ConcurrentHashMap<>();

    public WebTerminalHandler(List<ContainerManager> managers) {
        this.managers = managers;
    }

    @Override
    public void onOpen(VWsSession session) {
        String engineParam = session.getQueryParam("engine");
        String containerId = session.getQueryParam("id");

        if (engineParam == null || containerId == null) {
            session.send("Error: engine and id query parameters are required.\r\n");
            session.close(1003, "Missing parameters");
            return;
        }

        ContainerManager targetManager = null;
        for (ContainerManager m : managers) {
            if (m.getEngineType().name().equalsIgnoreCase(engineParam)) {
                targetManager = m;
                break;
            }
        }

        if (targetManager == null) {
            session.send("Error: Unsupported engine type: " + engineParam + "\r\n");
            session.close(1003, "Unsupported engine");
            return;
        }

        try {
            // Attempt to start bash, fallback to sh if it fails
            VProcessService.VProcessHandle handle;
            try {
                handle = targetManager.executeInteractive(containerId, List.of("bash"), VTrustLevel.FULL);
            } catch (Exception e) {
                handle = targetManager.executeInteractive(containerId, List.of("sh"), VTrustLevel.FULL);
            }

            activeSessions.put(session, handle);

            // Start background reader threads using virtual threads
            final VProcessService.VProcessHandle finalHandle = handle;
            
            Thread.ofVirtual().start(() -> readStream(finalHandle.stdout(), session));
            Thread.ofVirtual().start(() -> readStream(finalHandle.stderr(), session));

        } catch (Exception e) {
            log.error("Failed to start terminal for container {}: {}", containerId, e.getMessage());
            session.send("Error: Failed to connect to container: " + e.getMessage() + "\r\n");
            session.close(1011, "Failed to start process");
        }
    }

    @Override
    public void onMessage(VWsSession session, String text, boolean last) {
        VProcessService.VProcessHandle handle = activeSessions.get(session);
        if (handle != null) {
            try {
                OutputStream stdin = handle.stdin();
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (Exception e) {
                log.error("Failed to write to container stdin: {}", e.getMessage());
                closeSession(session);
            }
        }
    }

    @Override
    public void onClose(VWsSession session, int statusCode, String reason) {
        closeSession(session);
    }

    @Override
    public void onError(VWsSession session, Throwable t) {
        log.warn("WebSocket error for session: {}", t.getMessage());
        closeSession(session);
    }

    private void readStream(InputStream in, VWsSession session) {
        byte[] buffer = new byte[1024];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                session.send(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // Stream closed or error
        } finally {
            closeSession(session);
        }
    }

    private void closeSession(VWsSession session) {
        VProcessService.VProcessHandle handle = activeSessions.remove(session);
        if (handle != null) {
            // Clean up resources / stop child process
            try {
                handle.stdin().close();
                handle.stdout().close();
                handle.stderr().close();
            } catch (Exception ignored) {}
        }
    }
}
