package dev.vatn.core.cli;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VService;
import dev.vatn.api.cli.VCliContext;
import dev.vatn.api.cli.VCliContextMode;
import dev.vatn.core.VNodeRunner;

import java.io.PrintStream;
import java.net.ServerSocket;
import java.util.Optional;

/**
 * Factory for {@link VCliContext} instances backing the declared command contexts. Lives in
 * vatn-core because booting a node requires {@link VNodeRunner}; the SPI itself stays
 * dependency-light in vatn-api.
 */
public final class CliContexts {

    private CliContexts() {}

    /** Build a context for the given mode. (REMOTE/ATTACH fall back to ephemeral until X3.) */
    public static VCliContext forMode(VCliContextMode mode) {
        return mode == VCliContextMode.NONE ? none() : ephemeral();
    }

    /** No node — for pure commands ({@code context = NONE}). */
    public static VCliContext none() {
        return new SimpleContext(null, null);
    }

    /**
     * Boots a minimal in-process node on an ephemeral port and exposes its system services
     * (VProcessService, VJson, persistence, …). {@link VCliContext#close()} stops it.
     */
    public static VCliContext ephemeral() {
        VNodeRunner runner = new VNodeRunnerHolder().boot();
        return new SimpleContext(runner, runner.getContext());
    }

    /** Holder that picks a free port and starts a bare node. */
    private static final class VNodeRunnerHolder {
        VNodeRunner boot() {
            int port = freePort();
            VNodeRunner runner = VNodeRunner.create(port);
            runner.start();
            return runner;
        }
        private static int freePort() {
            try (ServerSocket s = new ServerSocket(0)) {
                return s.getLocalPort();
            } catch (Exception e) {
                return 47_321; // fallback
            }
        }
    }

    private static final class SimpleContext implements VCliContext {
        private final VNodeRunner runner;     // nullable
        private final VNodeContext node;      // nullable

        SimpleContext(VNodeRunner runner, VNodeContext node) {
            this.runner = runner;
            this.node = node;
        }

        @Override public VNodeContext node() { return node; }

        @Override
        public <T extends VService> Optional<T> getService(Class<T> type) {
            return node != null ? node.getService(type) : Optional.empty();
        }

        @Override public PrintStream out() { return System.out; }
        @Override public PrintStream err() { return System.err; }

        @Override
        public void close() {
            if (runner != null) {
                try { runner.stop(); } catch (Exception ignored) { }
            }
        }
    }
}
