package dev.vatn.junit;

import dev.vatn.api.VNodeContext;
import dev.vatn.core.test.VTestHarness;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * JUnit 5 Extension for VATN Testing.
 * Bootstraps a VTestHarness with an isolated environment and provides dependency injection
 * for VNodeContext and VTestHarness into test methods/constructors.
 */
public class VatnTestExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(VatnTestExtension.class);
    private static final String HARNESS_KEY = "v_test_harness";
    private static final String TEMP_DIR_KEY = "v_temp_dir";

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // Create a unique temporary home for this test class
        Path tempDir = Files.createTempDirectory("vatn-test-run-");
        context.getStore(NAMESPACE).put(TEMP_DIR_KEY, tempDir);

        // Initialize the harness on a random port (0 for ephemeral)
        VTestHarness harness = new VTestHarness(0, tempDir, true);
        harness.start();

        context.getStore(NAMESPACE).put(HARNESS_KEY, harness);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        VTestHarness harness = context.getStore(NAMESPACE).get(HARNESS_KEY, VTestHarness.class);
        if (harness != null) {
            harness.stop();
        }

        // Cleanup temp directory
        Path tempDir = context.getStore(NAMESPACE).get(TEMP_DIR_KEY, Path.class);
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try { Files.delete(p); } catch (Exception ignored) {}
                 });
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type == VNodeContext.class || type == VTestHarness.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        VTestHarness harness = extensionContext.getStore(NAMESPACE).get(HARNESS_KEY, VTestHarness.class);
        Class<?> type = parameterContext.getParameter().getType();
        
        if (type == VTestHarness.class) return harness;
        if (type == VNodeContext.class) return harness.getContext();
        
        return null;
    }
}
