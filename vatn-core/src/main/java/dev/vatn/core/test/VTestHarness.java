package dev.vatn.core.test;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VWatchdogService;
import dev.vatn.core.VNodeRunner;
import dev.vatn.core.supervisor.VWatchdogServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Specialized test harness that runs a VATN Node in a synthetic environment.
 * Disables encryption, uses mock messaging, and enables strict limit enforcement.
 *
 * <p>Extended with:
 * <ul>
 *   <li>{@link #getBoundPort()} — the actual OS-assigned port after start</li>
 *   <li>{@link #addPlugin(VNodePlugin)} — load a plugin before calling {@link #start()}</li>
 *   <li>{@link #getRunner()} — direct access to the underlying runner when needed</li>
 * </ul>
 */
public class VTestHarness {
    private static final Logger log = LoggerFactory.getLogger(VTestHarness.class);

    private final VNodeRunner runner;
    private final MockMessagingImpl mockMessaging;
    private final VWatchdogServiceImpl watchdog;

    public VTestHarness(int port, Path homeDir, boolean insecureMode) {
        this.mockMessaging = new MockMessagingImpl();

        // Use the isolated home directory for all persistence
        System.setProperty("user.home", homeDir.toAbsolutePath().toString());

        this.runner = VNodeRunner.create(port);
        this.runner.setMessagingOverride(mockMessaging);

        // Initialize Watchdog for stability audits
        this.watchdog = new VWatchdogServiceImpl(null);
        this.runner.registerService(VWatchdogService.class, watchdog);

        if (insecureMode) {
            log.warn("⚠️ [WARNING: INSECURE TEST MODE] Encryption and OIPC signing are DISABLED for speed.");
        }
    }

    /**
     * Add a plugin to the node. Must be called <em>before</em> {@link #start()}.
     *
     * @param plugin plugin instance to load
     */
    public void addPlugin(VNodePlugin plugin) {
        runner.addPlugin(plugin);
    }

    public void start() {
        watchdog.start();
        runner.start();

        // Configure Watchdog for strict testing (Hang/Leak detection)
        watchdog.setFailOnLimits(true);
    }

    public void stop() {
        runner.stop();
    }

    /** Returns the actual HTTP port the test node is listening on. */
    public int getBoundPort() {
        return runner.getBoundPort();
    }

    public VNodeContext getContext() {
        return runner.getContext();
    }

    /** Direct access to the underlying runner (for advanced test scenarios). */
    public VNodeRunner getRunner() {
        return runner;
    }

    public MockMessagingImpl getMockMessaging() {
        return mockMessaging;
    }
}
