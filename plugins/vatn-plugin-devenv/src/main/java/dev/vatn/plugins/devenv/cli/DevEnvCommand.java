package dev.vatn.plugins.devenv.cli;

import dev.vatn.api.VJson;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VProcessService;
import dev.vatn.api.cli.VCliCommand;
import dev.vatn.api.cli.VCliContext;
import dev.vatn.plugins.devenv.DevEnvConfig;
import dev.vatn.plugins.devenv.DevEnvServiceImpl;
import dev.vatn.plugins.devenv.model.DevEnvSnapshot;
import dev.vatn.plugins.devenv.scanner.ScannerUtil;

import java.util.List;

/**
 * CLI entry point for the devenv scanner — the first consumer of VATN's CLI extensibility.
 * Contributed to a host CLI (e.g. {@code vatn devenv scan}) via ServiceLoader.
 *
 * <p>Runs under an {@code EPHEMERAL_NODE} context (the default), so it gets a live
 * {@link VProcessService} and {@link VJson} from the booted node without the plugin author
 * touching vatn-core.
 */
public final class DevEnvCommand implements VCliCommand {

    @Override
    public String name() {
        return "devenv";
    }

    @Override
    public String summary() {
        return "Scan the local developer environment (runtimes, managers, venvs, "
                + "containers, k8s, agents, MCP, accelerators, Apple)";
    }

    @Override
    public int run(List<String> args, VCliContext ctx) {
        String sub = args.isEmpty() ? "scan" : args.get(0);
        if (!"scan".equals(sub)) {
            ctx.err().println("usage: vatn devenv scan        (serve mode arrives with X3)");
            return 2;
        }

        VNodeContext node = ctx.node();
        if (node == null) {
            ctx.err().println("devenv scan needs a node context");
            return 1;
        }
        VJson json = node.getJson();
        VProcessService proc = ctx.getService(VProcessService.class).orElse(null);

        DevEnvConfig cfg = DevEnvConfig.defaults();
        ScannerUtil util = new ScannerUtil(proc, cfg.getSubprocessTimeout());
        DevEnvSnapshot snapshot = new DevEnvServiceImpl(cfg, util, json).scan();

        ctx.out().println(json.stringify(snapshot));
        return 0;
    }
}
