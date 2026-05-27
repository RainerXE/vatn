package dev.vatn.core.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the VNativeBridge logic in JVM mode.
 * Tests call doStartNode() directly to avoid GraalVM Word-type null issues —
 * CCharPointer cannot be passed as Java null in JVM mode.
 */
public class NativeBridgeTest {

    @Test
    public void testBridgeLifecycle() {
        long handle = VNativeBridge.doStartNode("{}");
        assertTrue(handle > 0, "doStartNode should return a positive handle");
        VNativeBridge.stopNode(null, handle);
    }

    @Test
    public void testErrorCapture() {
        // Null/blank config must default to "{}" and return a valid handle.
        long handle = VNativeBridge.doStartNode(null);
        assertTrue(handle > 0, "Null config should default to working handle");
        VNativeBridge.stopNode(null, handle);
    }
}
