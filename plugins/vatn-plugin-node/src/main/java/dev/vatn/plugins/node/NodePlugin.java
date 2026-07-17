package dev.vatn.plugins.node;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * VATN Node.js Runtime Plugin — sandboxed Node.js process manager.
 *
 * <p>Paths are read from {@code .vatn/vatn.toml} at startup (programmatic config wins):
 * <pre>
 * [node]
 * apps_dir    = "/data/vatn/node/apps"
 * node_binary = "node"
 * npm_binary  = "npm"
 * </pre>
 *
 * <p>Admin UI (disk-space view + path configuration): {@code GET /node/ui}
 */
public class NodePlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(NodePlugin.class);

    private final NodeConfig     config;
    private NodeRuntime          runtime;
    private NodeProcessManager   manager;

    public NodePlugin() { this(NodeConfig.builder().build()); }
    public NodePlugin(NodeConfig config) { this.config = config; }

    @Override public String getId()      { return "dev.vatn.plugins.node"; }
    @Override public String getName()    { return "VATN Node.js Runtime"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Initializing VATN Node.js Runtime Plugin");
        try {
            Path workspacePath = ctx.getWorkspacePath();
            NodeConfig effective = mergeWithToml(config, workspacePath);
            RuntimeTomlConfig tomlConfig = new RuntimeTomlConfig(workspacePath, "node");

            runtime = new NodeRuntime(effective, workspacePath);
            runtime.initialize();
            manager = new NodeProcessManager(effective);

            ctx.registerService(NodeRuntime.class,        runtime);
            ctx.registerService(NodeProcessManager.class, manager);
            ctx.register("/node", new NodeHttpService(runtime, manager, tomlConfig));
            ctx.registerHealthCheck("node", () -> runtime.isHealthy());

            log.info("Node.js plugin ready — {} | npm={} | apps={} | appsDir={} | UI: /node/ui",
                runtime.nodeVersion(), runtime.npmVersion(),
                runtime.appNames().size(), runtime.appsDir());
        } catch (Exception e) {
            log.error("Failed to initialize Node.js plugin: {}", e.getMessage(), e);
            throw new RuntimeException("Node.js plugin initialization failed", e);
        }
    }

    @Override
    public void onShutdown() {
        log.info("Node.js plugin shutting down — stopping all processes");
        if (manager != null) manager.stopAll();
    }

    private static NodeConfig mergeWithToml(NodeConfig base, Path workspacePath) {
        Map<String, String> toml = new RuntimeTomlConfig(workspacePath, "node").read();
        if (toml.isEmpty()) return base;

        NodeConfig.Builder b = NodeConfig.builder();
        b.nodeBinary(base.nodeBinary() != null ? base.nodeBinary() : toml.get("node_binary"));
        b.npmBinary(base.npmBinary()   != null ? base.npmBinary()  : toml.get("npm_binary"));
        b.npxBinary(base.npxBinary()   != null ? base.npxBinary()  : toml.get("npx_binary"));
        b.appsDir(base.appsDir() != null ? base.appsDir()
            : (toml.containsKey("apps_dir") ? Paths.get(toml.get("apps_dir")) : null));
        b.restartDelayMs(base.restartDelayMs()).maxLogLines(base.maxLogLines())
         .allowNetwork(base.allowNetwork()).allowedEnvVars(base.allowedEnvVars());

        NodeConfig merged = b.build();
        log.info("[NODE] Effective config: appsDir={}",
            merged.appsDir() != null ? merged.appsDir() : "(default)");
        return merged;
    }
}
