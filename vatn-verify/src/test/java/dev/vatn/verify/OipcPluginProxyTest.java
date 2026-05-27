package dev.vatn.verify;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VPluginDescriptor;
import dev.vatn.core.VNodeRunner;
import dev.vatn.core.supervisor.VWatchdogServiceImpl;
import dev.vatn.spec.VPluginManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class OipcPluginProxyTest {

    private Path tempPythonScript;
    private VNodeContext context;
    private VWatchdogServiceImpl watchdog;

    @BeforeEach
    public void setup() throws Exception {
        // Create the dummy plugin python script
        tempPythonScript = Paths.get("target", "dummy_plugin.py");
        String pythonCode =
                "import os\n" +
                "import socket\n" +
                "import sys\n" +
                "port = int(os.environ.get('VATN_IPC_PORT', '0'))\n" +
                "if port == 0: sys.exit(1)\n" +
                "with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:\n" +
                "    s.bind(('127.0.0.1', port))\n" +
                "    s.listen()\n" +
                "    conn, addr = s.accept()\n" +
                "    with conn:\n" +
                "        line = conn.recv(1024).decode('utf-8')\n" +
                "        if 'VATN_INIT' in line:\n" +
                "            conn.sendall(b'{\"type\":\"ACK\",\"status\":\"READY\"}\\n')\n" +
                "            while True:\n" +
                "                data = conn.recv(1024)\n" +
                "                if not data or 'VATN_SHUTDOWN' in data.decode('utf-8'):\n" +
                "                    break\n";

        Files.writeString(tempPythonScript, pythonCode);

        // Start local node
        VNodeRunner runner = VNodeRunner.create(0, Paths.get("target/test-plugins"), Paths.get("target/oipc-test.pem"));
        runner.start();
        context = runner.getContext();
        watchdog = new VWatchdogServiceImpl(context);
        watchdog.start();
    }

    @AfterEach
    public void teardown() throws Exception {
        watchdog.stop();
        if (Files.exists(tempPythonScript)) {
            Files.delete(tempPythonScript);
        }
    }

    @Test
    public void testExternalPythonProcessIsolation() throws Exception {
        // Create the descriptor matching the JSON contract
        VPluginManifest manifest = new VPluginManifest();
        manifest.setId("dev.example.tools.dummy-python");
        manifest.setName("Dummy Python Ext");
        
        VPluginManifest.Execution execution = new VPluginManifest.Execution();
        execution.setMode("OUT_OF_PROCESS_BIN");
        execution.setTransport("TCP_LOOPBACK");
        execution.setEntrypoint("python3 " + tempPythonScript.toAbsolutePath().toString());
        manifest.setExecution(execution);

        VPluginDescriptor descriptor = new VPluginDescriptor() {
            @Override
            public String getPluginId() { return manifest.getId(); }
            @Override
            public VPluginManifest getManifest() { return manifest; }
            @Override
            public Path getSourcePath() { return tempPythonScript; }
        };

        // Command Watchdog to span the python script via IPC
        watchdog.supervise(manifest.getId(), descriptor);

        // Allow some time for process and handshake over TCP
        Thread.sleep(1500);

        // Validate the python process is actually running independently by the JVM
        assertTrue(watchdog.isHealthy(manifest.getId()), "Python plugin process should be running healthily");

        // Validate graceful teardown
        watchdog.drop(manifest.getId());
        Thread.sleep(500);
        assertTrue(!watchdog.isHealthy(manifest.getId()), "Python process should be terminated");
    }
}
