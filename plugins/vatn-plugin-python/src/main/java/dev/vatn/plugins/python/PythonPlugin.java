package dev.vatn.plugins.python;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * VATN Python Runtime Plugin — Pinokio-compatible sandboxed Python execution.
 *
 * <p>Paths are read from {@code .vatn/vatn.toml} at startup (programmatic config wins):
 * <pre>
 * [python]
 * envs_dir      = "/data/vatn/python/envs"
 * apps_dir      = "/data/vatn/python/apps"
 * python_binary = "python3.12"
 * prefer_uv     = true
 * </pre>
 *
 * <p>Admin UI (disk-space view + path configuration): {@code GET /python/ui}
 */
public class PythonPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(PythonPlugin.class);

    private final PythonConfig   config;
    private PythonRuntime        runtime;
    private PythonProcessManager manager;

    public PythonPlugin() { this(PythonConfig.builder().build()); }

    public PythonPlugin(PythonConfig config) { this.config = config; }

    @Override public String getId()      { return "dev.vatn.plugins.python"; }
    @Override public String getName()    { return "VATN Python Runtime"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Initializing VATN Python Runtime Plugin");
        try {
            Path workspacePath = ctx.getWorkspacePath();
            PythonConfig effective = mergeWithToml(config, workspacePath);
            RuntimeTomlConfig tomlConfig = new RuntimeTomlConfig(workspacePath, "python");

            runtime = new PythonRuntime(effective, workspacePath);
            runtime.initialize();
            manager = new PythonProcessManager(effective);

            ctx.registerService(PythonRuntime.class,        runtime);
            ctx.registerService(PythonProcessManager.class, manager);
            ctx.register("/python", new PythonHttpService(runtime, manager, tomlConfig));
            ctx.registerHealthCheck("python", () -> runtime.isHealthy());

            log.info("Python plugin ready — {} | uv={} | envs={} | envsDir={} | UI: /python/ui",
                runtime.pythonVersion(), runtime.uvAvailable(),
                runtime.envNames().size(), runtime.envsDir());
        } catch (Exception e) {
            log.error("Failed to initialize Python plugin: {}", e.getMessage(), e);
            throw new RuntimeException("Python plugin initialization failed", e);
        }
    }

    @Override
    public void onShutdown() {
        log.info("Python plugin shutting down — stopping all processes");
        if (manager != null) manager.stopAll();
    }

    private static PythonConfig mergeWithToml(PythonConfig base, Path workspacePath) {
        Map<String, String> toml = new RuntimeTomlConfig(workspacePath, "python").read();
        if (toml.isEmpty()) return base;

        PythonConfig.Builder b = PythonConfig.builder();
        b.pythonBinary(base.pythonBinary() != null ? base.pythonBinary() : toml.get("python_binary"));
        b.envsDir(base.envsDir() != null ? base.envsDir()
            : (toml.containsKey("envs_dir") ? Paths.get(toml.get("envs_dir")) : null));
        b.appsDir(base.appsDir() != null ? base.appsDir()
            : (toml.containsKey("apps_dir") ? Paths.get(toml.get("apps_dir")) : null));

        boolean preferUv = base.preferUv();
        if (toml.containsKey("prefer_uv")) preferUv = !"false".equalsIgnoreCase(toml.get("prefer_uv"));
        b.preferUv(preferUv).restartDelayMs(base.restartDelayMs())
         .maxLogLines(base.maxLogLines()).allowedEnvVars(base.allowedEnvVars());

        PythonConfig merged = b.build();
        log.info("[PYTHON] Effective config: envsDir={}, appsDir={}",
            merged.envsDir() != null ? merged.envsDir() : "(default)", merged.appsDir() != null ? merged.appsDir() : "(default)");
        return merged;
    }
}
