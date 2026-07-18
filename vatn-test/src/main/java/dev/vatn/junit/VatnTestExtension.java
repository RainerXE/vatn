package dev.vatn.junit;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.core.test.VTestHarness;
import org.junit.jupiter.api.extension.*;

import java.lang.annotation.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Enhanced JUnit 5 Extension for VATN testing.
 *
 * <p>Used via {@code @VatnTest} (class-level) or via {@code @RegisterExtension}
 * with a {@link VatnTestBuilder} for full control:
 *
 * <pre>{@code
 * // Simple — just annotate:
 * @VatnTest
 * class FooTest { @Test void t(VNodeContext ctx) {} }
 *
 * // With plugins:
 * @ExtendWith({})   // suppress @VatnTest if using @RegisterExtension
 * class AuthTest {
 *     @RegisterExtension
 *     static final VatnTestExtension EXT = VatnTestBuilder.node()
 *         .withPlugin(new AuthPlugin(AuthConfig.of("my-32-char-secret!!!!!", (u, p) -> Map.of())))
 *         .buildExtension();
 *
 *     @Test void login(VNodeContext ctx, @VatnTestExtension.VatnPort int port) {}
 * }
 * }</pre>
 *
 * <p>Parameter injection:
 * <ul>
 *   <li>{@link VTestHarness} — the full harness</li>
 *   <li>{@link VNodeContext}  — the node's context</li>
 *   <li>{@code int} annotated with {@link VatnPort} — the HTTP port</li>
 * </ul>
 */
public class VatnTestExtension
        implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(VatnTestExtension.class);

    private static final String HARNESS_KEY = "harness";
    private static final String TEMPDIR_KEY = "tempdir";
    private static final String PORT_KEY    = "port";

    /** Stored when created via {@link VatnTestBuilder#buildExtension()}. */
    private VatnTestBuilder builder;

    // Package-private: called by VatnTestBuilder
    void storeBuilder(VatnTestBuilder b) {
        this.builder = b;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void beforeAll(ExtensionContext ctx) throws Exception {
        Path tempDir = Files.createTempDirectory("vatn-test-");
        ctx.getStore(NS).put(TEMPDIR_KEY, tempDir);

        int port = (builder != null) ? builder.port() : 0;
        VTestHarness harness = new VTestHarness(port, tempDir, /* insecureMode */ true);

        // Load plugins provided via VatnTestBuilder
        if (builder != null) {
            for (VNodePlugin plugin : builder.plugins()) {
                harness.addPlugin(plugin);
            }
        }

        harness.start();

        ctx.getStore(NS).put(HARNESS_KEY, harness);
        ctx.getStore(NS).put(PORT_KEY,    harness.getBoundPort());
    }

    @Override
    public void afterAll(ExtensionContext ctx) throws Exception {
        VTestHarness harness = ctx.getStore(NS).get(HARNESS_KEY, VTestHarness.class);
        if (harness != null) harness.stop();

        Path tempDir = ctx.getStore(NS).get(TEMPDIR_KEY, Path.class);
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    // ── Parameter resolution ──────────────────────────────────────────────────

    @Override
    public boolean supportsParameter(ParameterContext pCtx, ExtensionContext eCtx) {
        Class<?> type = pCtx.getParameter().getType();
        return type == VTestHarness.class
            || type == VNodeContext.class
            || ((type == int.class || type == Integer.class) && pCtx.isAnnotated(VatnPort.class));
    }

    @Override
    public Object resolveParameter(ParameterContext pCtx, ExtensionContext eCtx) {
        VTestHarness harness = eCtx.getStore(NS).get(HARNESS_KEY, VTestHarness.class);
        Class<?> type = pCtx.getParameter().getType();

        if (type == VTestHarness.class) return harness;
        if (type == VNodeContext.class)  return harness.getContext();
        if (pCtx.isAnnotated(VatnPort.class)) {
            Integer p = eCtx.getStore(NS).get(PORT_KEY, Integer.class);
            return p != null ? p : 0;
        }
        throw new ParameterResolutionException("Unsupported parameter: " + pCtx.getParameter());
    }

    // ── Annotations ───────────────────────────────────────────────────────────

    /**
     * Marks an {@code int} parameter for injection of the bound HTTP port
     * of the test node.
     *
     * <pre>{@code
     * @Test void t(@VatnTestExtension.VatnPort int port) {
     *     assertThat(port).isGreaterThan(0);
     * }
     * }</pre>
     */
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface VatnPort {}
}
