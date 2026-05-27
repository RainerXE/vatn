package dev.vatn.core.plugin;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VPluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Acts as a mirror/proxy inside the VATN node for an external out-of-process plugin
 * running in a separate OS process. Translates VMessaging publish/subscribe 
 * calls into OIPC JSON boundaries sent over a raw TCP loopback socket.
 */
public class OipcProcessPluginProxy implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(OipcProcessPluginProxy.class);

    private final VPluginDescriptor descriptor;
    private final int ipcPort;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = false;
    private Thread listenerThread;
    private Runnable heartbeatCallback;

    public OipcProcessPluginProxy(VPluginDescriptor descriptor, int ipcPort) {
        this.descriptor = descriptor;
        this.ipcPort = ipcPort;
    }

    public void setHeartbeatCallback(Runnable callback) {
        this.heartbeatCallback = callback;
    }

    @Override
    public String getId() {
        return descriptor.getPluginId();
    }

    @Override
    public String getName() {
        return descriptor.getManifest() != null ? descriptor.getManifest().getName() : descriptor.getPluginId();
    }

    @Override
    public String getVersion() {
        return descriptor.getManifest() != null ? descriptor.getManifest().getVersion() : "1.0.0";
    }

    @Override
    public void onInitialize(VNodeContext context) {
        log.info("Initializing OIPC Proxy for {} connecting to localhost:{}", getId(), ipcPort);
        
        try {
            // Establish the IPC connection to the external process.
            socket = new Socket("127.0.0.1", ipcPort);
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            
            // Handshake (OIPC v2.12 legacy JSON mode — see docs/oipc-protocol.md §6)
            out.println("{\"type\":\"VATN_INIT\",\"pluginId\":\"" + getId() + "\"}");
            String ack = in.readLine();
            log.info("Received IPC handshake ACK: {}", ack);

            running = true;
            // Background thread to listen for IPC messages from the Python/Rust binary
            listenerThread = new Thread(this::listenLoop, "oipc-listener-" + getId());
            listenerThread.setDaemon(true);
            listenerThread.start();

        } catch (java.io.IOException e) {
            log.error("Failed to establish IPC loopback connection to {}", getId(), e);
            throw new RuntimeException("OIPC Handshake failed", e);
        }
    }

    private void listenLoop() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (heartbeatCallback != null) heartbeatCallback.run();
                log.debug("[{} IPC-IN] {}", getId(), line);
            }
        } catch (java.io.IOException e) {
            if (running) log.error("IPC read loop failed for {}", getId(), e);
        }
    }

    public void ping() {
        if (out != null) {
            log.trace("Sending STATUS_CHECK to {}", getId());
            // We use a non-blocking output if possible, but PrintWriter is ok for now.
            out.println("{\"type\":\"STATUS_CHECK\"}");
        }
    }

    @Override
    public void onShutdown() {
        running = false;
        try {
            if (out != null) {
                out.println("{\"type\":\"VATN_SHUTDOWN\"}");
                out.close();
            }
            if (in != null) in.close();
            if (socket != null) socket.close();
            if (listenerThread != null) listenerThread.interrupt();
        } catch (java.io.IOException e) {
            log.warn("Error closing IPC socket for {}", getId());
        }
    }
}
