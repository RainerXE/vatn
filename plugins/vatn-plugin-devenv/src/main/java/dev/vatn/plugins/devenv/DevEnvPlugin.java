package dev.vatn.plugins.devenv;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.VProcessService;
import dev.vatn.plugins.devenv.scanner.ScannerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VATN plugin entry point for the developer-environment inventory.
 *
 * <pre>{@code
 *   VNodeRunner node = VNodeRunner.create(8080);
 *   node.addPlugin(new DevEnvPlugin());
 *   node.start();
 *   // GET http://127.0.0.1:8080/devenv/snapshot
 * }</pre>
 */
public final class DevEnvPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(DevEnvPlugin.class);

    private final DevEnvConfig config;
    private DevEnvServiceImpl service;

    public DevEnvPlugin() { this(DevEnvConfig.defaults()); }

    public DevEnvPlugin(DevEnvConfig config) { this.config = config; }

    @Override public String getId()      { return "dev.vatn.plugins.devenv"; }
    @Override public String getName()    { return "VATN DevEnv Scanner"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        // Subprocess probes go through VATN, not raw ProcessBuilder (DCN-DEV-11).
        VProcessService proc = ctx.getService(VProcessService.class).orElse(null);
        if (proc == null) {
            log.warn("VProcessService unavailable — DevEnv will use a ProcessBuilder fallback");
        }
        ScannerUtil util = new ScannerUtil(proc, config.getSubprocessTimeout());

        service = new DevEnvServiceImpl(config, util, ctx.getJson());
        service.start();

        ctx.registerService(DevEnvService.class, service);
        ctx.register("/devenv", new DevEnvHttpService(service, ctx.getJson()));
        ctx.registerHealthCheck("devenv", () -> service.lastSnapshot().isPresent());

        log.info("vatn-plugin-devenv v{} initialized — dashboard/API at /devenv", getVersion());
    }

    @Override
    public void onShutdown() {
        if (service != null) service.stop();
    }
}
