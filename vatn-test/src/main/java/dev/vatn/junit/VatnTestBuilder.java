package dev.vatn.junit;

import dev.vatn.api.VNodePlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for configuring a VATN test node.
 *
 * <p>Use with {@code @RegisterExtension} to customise the node before it starts:
 * <pre>{@code
 * @RegisterExtension
 * static final VatnTestExtension EXT = VatnTestBuilder.node()
 *     .withPlugin(new AuthPlugin(AuthConfig.of("my-32-char-secret-key-here!!!!!", (u, p) -> Map.of())))
 *     .withPort(0)
 *     .buildExtension();
 * }</pre>
 */
public final class VatnTestBuilder {

    private int port = 0;  // 0 = ephemeral / OS-assigned
    private final List<VNodePlugin> plugins = new ArrayList<>();

    private VatnTestBuilder() {}

    /** Start building a test node. */
    public static VatnTestBuilder node() {
        return new VatnTestBuilder();
    }

    /** Set a fixed port. Use {@code 0} for an OS-assigned ephemeral port (default). */
    public VatnTestBuilder withPort(int port) {
        this.port = port;
        return this;
    }

    /** Add a plugin to be loaded into the test node before it starts. */
    public VatnTestBuilder withPlugin(VNodePlugin plugin) {
        this.plugins.add(plugin);
        return this;
    }

    /** Add multiple plugins. */
    public VatnTestBuilder withPlugins(VNodePlugin... plugins) {
        for (VNodePlugin p : plugins) this.plugins.add(p);
        return this;
    }

    // ── Accessors (used by VatnTestExtension) ─────────────────────────────────

    int port() { return port; }

    List<VNodePlugin> plugins() { return List.copyOf(plugins); }

    /**
     * Build and return a {@link VatnTestExtension} pre-configured with this builder.
     * Store the result in a {@code static final} field annotated with
     * {@code @RegisterExtension}.
     */
    public VatnTestExtension buildExtension() {
        VatnTestExtension ext = new VatnTestExtension();
        // Stash ourselves so beforeAll can retrieve the config
        ext.storeBuilder(this);
        return ext;
    }
}
