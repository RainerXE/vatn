package dev.vatn.api;

import dev.vatn.api.workflow.VDagEngine;
import dev.vatn.api.workflow.VDagRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VNodeContextAccessorsTest {

    /** Minimal stub context: every typed accessor except the two defaults is unsupported. */
    static class StubContext implements VNodeContext {
        private final java.util.Map<Class<?>, VService> services = new java.util.HashMap<>();
        @Override public VMessaging getMessaging() { throw new UnsupportedOperationException(); }
        @Override public VStream getStream() { throw new UnsupportedOperationException(); }
        @Override public VPluginRegistry getPluginRegistry() { throw new UnsupportedOperationException(); }
        @Override public VMemoryChannel getMemory() { throw new UnsupportedOperationException(); }
        @Override public VJson getJson() { throw new UnsupportedOperationException(); }
        @Override public VConfiguration getConfiguration() { throw new UnsupportedOperationException(); }
        @Override public VClockService getClock() { throw new UnsupportedOperationException(); }
        @Override public VGuardService getGuard() { throw new UnsupportedOperationException(); }
        @Override public dev.vatn.api.security.VSecretService getSecrets() { throw new UnsupportedOperationException(); }
        @Override public VDiscovery getDiscovery() { throw new UnsupportedOperationException(); }
        @Override @SuppressWarnings("unchecked")
        public <T extends VService> Optional<T> getService(Class<T> t) { return Optional.ofNullable((T) services.get(t)); }
        @Override public <T extends VService> void registerService(Class<T> t, T impl) { services.put(t, impl); }
        @Override public void register(String path, VHttpService service) {}
        @Override public String getNodeId() { return "stub"; }
        @Override public java.nio.file.Path getWorkspacePath() { return java.nio.file.Path.of("."); }
    }

    @Test
    void dagAccessorsDelegateToGetService() {
        StubContext ctx = new StubContext();
        VDagEngine engineProxy = (VDagEngine) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{VDagEngine.class},
                (p, m, a) -> { throw new UnsupportedOperationException(); });
        VDagRegistry regProxy = (VDagRegistry) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{VDagRegistry.class},
                (p, m, a) -> { throw new UnsupportedOperationException(); });
        ctx.registerService(VDagEngine.class, engineProxy);
        ctx.registerService(VDagRegistry.class, regProxy);
        assertSame(engineProxy, ctx.getDagEngine());
        assertSame(regProxy, ctx.getDagRegistry());
    }

    @Test
    void missingDagServicesThrowClearException() {
        StubContext ctx = new StubContext();
        IllegalStateException e = assertThrows(IllegalStateException.class, ctx::getDagEngine);
        assertTrue(e.getMessage().contains("VDagEngine"));
        assertThrows(IllegalStateException.class, ctx::getDagRegistry);
    }
}
