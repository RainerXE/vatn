package dev.vatn.plugins.containers;

import dev.vatn.api.*;
import dev.vatn.api.admin.VWorkload;
import dev.vatn.api.admin.VWorkloadProvider;
import dev.vatn.api.admin.VWorkloadRegistry;
import dev.vatn.api.security.VTrustLevel;
import dev.vatn.core.VJsonImpl;
import dev.vatn.core.VNodeContextImpl;
import dev.vatn.core.security.VFirewallImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ContainersPluginTest {

    // ---- Sanitizer ----

    @Test
    void sanitizeHtmlEscapesAngleBrackets() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", Sanitizer.sanitizeHtml("<script>alert(1)</script>"));
    }

    @Test
    void sanitizeHtmlEscapesAmpersand() {
        assertEquals("&amp;", Sanitizer.sanitizeHtml("&"));
    }

    @Test
    void sanitizeHtmlEscapesQuotes() {
        assertEquals("&quot; &amp; &#39;", Sanitizer.sanitizeHtml("\" & '"));
    }

    @Test
    void sanitizeHtmlNullReturnsEmpty() {
        assertEquals("", Sanitizer.sanitizeHtml(null));
    }

    @Test
    void sanitizeHtmlPlainTextUnchanged() {
        assertEquals("hello world", Sanitizer.sanitizeHtml("hello world"));
    }

    @Test
    void sanitizeJsEscapesBackslashAndQuotes() {
        assertEquals("\\\\ \\\" \\'", Sanitizer.sanitizeJs("\\ \" '"));
    }

    @Test
    void sanitizeJsEscapesNewline() {
        assertEquals("hello\\nworld", Sanitizer.sanitizeJs("hello\nworld"));
    }

    @Test
    void sanitizeJsNullReturnsEmpty() {
        assertEquals("", Sanitizer.sanitizeJs(null));
    }

    // ---- OsDetector ----

    @Test
    void osDetectorIdentifiesFedora() {
        var fields = OsDetector.parseOsRelease("""
            ID=fedora
            PRETTY_NAME="Fedora Linux 40"
            ID_LIKE=""
            """);
        assertEquals("fedora", fields.get("ID"));
        assertEquals("Fedora Linux 40", fields.get("PRETTY_NAME"));
    }

    @Test
    void osDetectorIdentifiesFedoraDerivativeFromIdLike() {
        var fields = OsDetector.parseOsRelease("""
            ID=rhel
            ID_LIKE="fedora"
            PRETTY_NAME="Red Hat Enterprise Linux 9"
            """);
        assertEquals("rhel", fields.get("ID"));
        assertEquals("fedora", fields.get("ID_LIKE"));
    }

    @Test
    void osDetectorIdentifiesUbuntu() {
        var fields = OsDetector.parseOsRelease("""
            ID=ubuntu
            PRETTY_NAME="Ubuntu 24.04 LTS"
            ID_LIKE=debian
            """);
        assertEquals("ubuntu", fields.get("ID"));
        assertEquals("debian", fields.get("ID_LIKE"));
    }

    @Test
    void osDetectorParseOsReleaseEmpty() {
        var fields = OsDetector.parseOsRelease("");
        assertTrue(fields.isEmpty());
    }

    @Test
    void osDetectorParseOsReleaseSkipsNonKeyValueLines() {
        var fields = OsDetector.parseOsRelease("""
            # comment
            ID=fedora
            empty_line_test
            """);
        assertEquals("fedora", fields.get("ID"));
    }

    // ---- ToolboxManager ----

    @Test
    void toolboxManagerReturnsEmptyWhenBinaryMissing() {
        var ps = new MockProcessService()
                .withProbe("toolbox", new VProcessService.VProcessResult(1, "", "not found"));
        var mgr = new ToolboxManager(ps);
        assertTrue(mgr.listContainers().isEmpty());
    }

    @Test
    void toolboxManagerParsesListOutput() {
        var ps = new MockProcessService()
                .withProbe("toolbox", new VProcessService.VProcessResult(0, "toolbox version 0.0.99", ""))
                .withExec(List.of("toolbox", "list", "-c"),
                        new VProcessService.VProcessResult(0,
                                "CONTAINER ID  CONTAINER NAME  CREATED         STATUS   IMAGE NAME\n" +
                                "abc123def456  fedora-toolbox-40  2 weeks ago  running  registry.fedoraproject.org/fedora-toolbox:40\n" +
                                "def789abc012  ubuntu-toolbox   5 days ago    exited  docker.io/library/ubuntu:22.04",
                                ""));
        var mgr = new ToolboxManager(ps);
        var containers = mgr.listContainers();
        assertEquals(2, containers.size());
        assertEquals("abc123def456", containers.get(0).id());
        assertEquals("fedora-toolbox-40", containers.get(0).name());
        assertEquals("registry.fedoraproject.org/fedora-toolbox:40", containers.get(0).image());
        assertTrue(containers.get(0).isRunning());
        assertEquals(VContainerEngine.TOOLBOX, containers.get(0).engine());
        assertFalse(containers.get(1).isRunning());
    }

    @Test
    void toolboxManagerStartUsesPodman() {
        var ps = new MockProcessService();
        var mgr = new ToolboxManager(ps);
        mgr.startContainer("abc123");
        assertTrue(ps.executedCommands.contains(List.of("podman", "start", "abc123")));
    }

    @Test
    void toolboxManagerStopUsesPodman() {
        var ps = new MockProcessService();
        var mgr = new ToolboxManager(ps);
        mgr.stopContainer("def789");
        assertTrue(ps.executedCommands.contains(List.of("podman", "stop", "def789")));
    }

    // ---- GenericContainerManager ----

    @Test
    void genericContainerManagerReturnsEmptyWhenBinaryMissing() {
        var processService = new MockProcessService()
                .withProbe("docker", new VProcessService.VProcessResult(1, "", "not found"));
        var json = new VJsonImpl();
        var mgr = new GenericContainerManager(VContainerEngine.DOCKER, "docker", processService, json);
        assertTrue(mgr.listContainers().isEmpty());
    }

    @Test
    void genericContainerManagerParsesDockerPsOutput() {
        var json = new VJsonImpl();
        var processService = new MockProcessService()
                .withProbe("docker", new VProcessService.VProcessResult(0, "Docker version 24.0.0", ""))
                .withExec(List.of("docker", "ps", "-a", "--format", "{{json .}}"),
                        new VProcessService.VProcessResult(0,
                                "{\"ID\":\"abc123\",\"Names\":\"my-container\",\"Image\":\"nginx:latest\",\"State\":\"running\",\"Status\":\"Up 2 hours\"}\n" +
                                "{\"ID\":\"def456\",\"Names\":\"stopped-box\",\"Image\":\"alpine:3.18\",\"State\":\"exited\",\"Status\":\"Exited 0\"}",
                                ""));
        var mgr = new GenericContainerManager(VContainerEngine.DOCKER, "docker", processService, json);
        var containers = mgr.listContainers();
        assertEquals(2, containers.size());
        assertEquals("abc123", containers.get(0).id());
        assertEquals("my-container", containers.get(0).name());
        assertEquals("nginx:latest", containers.get(0).image());
        assertTrue(containers.get(0).isRunning());
        assertEquals(VContainerEngine.DOCKER, containers.get(0).engine());
        assertFalse(containers.get(1).isRunning());
    }

    @Test
    void genericContainerManagerStartCallsBinary() {
        var json = new VJsonImpl();
        var processService = new MockProcessService();
        var mgr = new GenericContainerManager(VContainerEngine.DOCKER, "docker", processService, json);
        mgr.startContainer("abc123");
        assertTrue(processService.executedCommands.contains(List.of("docker", "start", "abc123")));
    }

    @Test
    void genericContainerManagerStopCallsBinary() {
        var json = new VJsonImpl();
        var processService = new MockProcessService();
        var mgr = new GenericContainerManager(VContainerEngine.DOCKER, "docker", processService, json);
        mgr.stopContainer("abc123");
        assertTrue(processService.executedCommands.contains(List.of("docker", "stop", "abc123")));
    }

    @Test
    void genericContainerManagerSkipsMalformedJsonLines() {
        var json = new VJsonImpl();
        var processService = new MockProcessService()
                .withProbe("docker", new VProcessService.VProcessResult(0, "Docker version 24.0.0", ""))
                .withExec(List.of("docker", "ps", "-a", "--format", "{{json .}}"),
                        new VProcessService.VProcessResult(0, "{\"ID\":\"valid\",\"Names\":\"ok\",\"Image\":\"img\",\"State\":\"running\",\"Status\":\"Up\"}\nnot-json-line\n{\"ID\":\"valid2\",\"Names\":\"ok2\",\"Image\":\"img2\",\"State\":\"exited\",\"Status\":\"Exited\"}", ""));
        var mgr = new GenericContainerManager(VContainerEngine.DOCKER, "docker", processService, json);
        var containers = mgr.listContainers();
        assertEquals(2, containers.size());
    }

    // ---- DistroboxManager ----

    @Test
    void distroboxManagerReturnsEmptyWhenBinaryMissing() {
        var processService = new MockProcessService()
                .withProbe("distrobox", new VProcessService.VProcessResult(1, "", "not found"));
        var mgr = new DistroboxManager(processService);
        assertTrue(mgr.listContainers().isEmpty());
    }

    @Test
    void distroboxManagerParsesListOutput() {
        var processService = new MockProcessService()
                .withProbe("distrobox", new VProcessService.VProcessResult(0, "distrobox version 1.6.0", ""))
                .withExec(List.of("distrobox", "list", "--no-color"),
                        new VProcessService.VProcessResult(0,
                                "ID | NAME | STATUS | IMAGE\n" +
                                "ubuntu-box | Ubuntu Dev | Up 2 hours | ubuntu:22.04\n" +
                                "fedora-box | Fedora Work | Exited | fedora:38",
                                ""));
        var mgr = new DistroboxManager(processService);
        var containers = mgr.listContainers();
        assertEquals(2, containers.size());
        assertEquals("ubuntu-box", containers.get(0).id());
        assertEquals("Ubuntu Dev", containers.get(0).name());
        assertEquals("ubuntu:22.04", containers.get(0).image());
        assertTrue(containers.get(0).isRunning());
        assertEquals(VContainerEngine.DISTROBOX, containers.get(0).engine());
        assertFalse(containers.get(1).isRunning());
    }

    @Test
    void distroboxManagerStartCallsBinary() {
        var processService = new MockProcessService();
        var mgr = new DistroboxManager(processService);
        mgr.startContainer("ubuntu-box");
        assertTrue(processService.executedCommands.contains(List.of("distrobox", "start", "ubuntu-box")));
    }

    @Test
    void distroboxManagerStopCallsBinary() {
        var processService = new MockProcessService();
        var mgr = new DistroboxManager(processService);
        mgr.stopContainer("fedora-box");
        assertTrue(processService.executedCommands.contains(List.of("distrobox", "stop", "--yes", "fedora-box")));
    }

    // ---- ContainersWorkloadProvider ----

    @Test
    void workloadProviderReturnsRunningContainers() {
        var containers = List.of(
                new VContainer("id1", "running-box", VContainerEngine.DOCKER, "nginx:latest", "Up", true, Map.of()),
                new VContainer("id2", "stopped-box", VContainerEngine.PODMAN, "alpine:3.18", "Exited", false, Map.of())
        );
        var manager = new ContainerManager() {
            public VContainerEngine getEngineType() { return VContainerEngine.DOCKER; }
            public List<VContainer> listContainers() { return containers; }
            public void startContainer(String id) {}
            public void stopContainer(String id) {}
            public VProcessService.VProcessHandle executeInteractive(String id, List<String> cmd, VTrustLevel tl) { return null; }
        };
        var provider = new ContainersWorkloadProvider(List.of(manager));
        var workloads = provider.getActiveWorkloads();
        assertEquals(1, workloads.size());
        assertEquals("id1", workloads.get(0).id());
        assertEquals("nginx:latest", workloads.get(0).resourceUsage().get("image"));
        assertEquals("DOCKER", workloads.get(0).resourceUsage().get("engine"));
        assertEquals(VWorkload.Type.CONTAINER, workloads.get(0).type());
        assertEquals(VWorkload.Status.RUNNING, workloads.get(0).status());
    }

    // ---- Plugin Lifecycle ----

    @Test
    void pluginInitializesCorrectly() {
        var ctx = createContext();
        var plugin = new ContainersPlugin();
        plugin.onInitialize(ctx);

        assertFalse(ctx.getRegisteredRoutes().isEmpty());
        assertTrue(ctx.getRegisteredRoutes().contains("/vatn/containers"));
    }

    @Test
    void healthCheckRegisteredAndReturnsTrue() {
        var ctx = createContext();
        var plugin = new ContainersPlugin();
        plugin.onInitialize(ctx);

        var healthChecks = ctx.getHealthChecks();
        assertTrue(healthChecks.containsKey("containers"));
        assertTrue(healthChecks.get("containers").get());
    }

    @Test
    void pluginMetadata() {
        var plugin = new ContainersPlugin();
        assertEquals("plugin.containers", plugin.getId());
        assertEquals("Containers Management Plugin", plugin.getName());
        assertEquals("1.0-alpha.14-preview", plugin.getVersion());
    }

    @Test
    void adminContribution() {
        var plugin = new ContainersPlugin();
        assertEquals("containers", plugin.id());
        assertEquals("Containers", plugin.title());
        assertEquals("/vatn/containers", plugin.path());
        assertEquals("⊞", plugin.icon());
    }

    // ---- ContainerTemplate ----

    @Test
    void containerTemplateDefaults() {
        var t = new ContainerTemplate(null, "test", "", null, "nginx:latest", null, null, null, null, null, null, null, null, null, null, null, 0, 0);
        assertNotNull(t.id());
        assertEquals("test", t.name());
        assertTrue(t.ports().isEmpty());
        assertTrue(t.volumes().isEmpty());
        assertTrue(t.env().isEmpty());
        assertTrue(t.labels().isEmpty());
        assertTrue(t.postStartCommands().isEmpty());
    }

    // ---- JsonTemplateStore ----

    @Test
    void templateStoreSaveAndGet() throws Exception {
        var dir = Files.createTempDirectory("vatn-templates-");
        try {
            var store = new JsonTemplateStore(dir);
            var t = new ContainerTemplate(null, "my-web", "Web server template",
                "PODMAN", "nginx:latest", "web1", null, null, List.of("8080:80"),
                null, Map.of("ENV", "prod"), null, null, null, null, null, 0, 0);
            var saved = store.save(t);
            assertNotNull(saved.id());
            assertEquals("my-web", saved.name());

            var loaded = store.get(saved.id());
            assertTrue(loaded.isPresent());
            assertEquals("nginx:latest", loaded.get().image());
            assertEquals(List.of("8080:80"), loaded.get().ports());
            assertEquals("prod", loaded.get().env().get("ENV"));
        } finally {
            dir.toFile().deleteOnExit();
        }
    }

    @Test
    void templateStoreListAndDelete() throws Exception {
        var dir = Files.createTempDirectory("vatn-templates-");
        try {
            var store = new JsonTemplateStore(dir);
            var t1 = store.save(new ContainerTemplate(null, "alpha", "", null, "img1", null, null, null, null, null, null, null, null, null, null, null, 0, 0));
            var t2 = store.save(new ContainerTemplate(null, "beta", "", null, "img2", null, null, null, null, null, null, null, null, null, null, null, 0, 0));

            assertEquals(2, store.list().size());
            assertEquals("alpha", store.list().get(0).name());

            store.delete(t2.id());
            assertEquals(1, store.list().size());
            assertTrue(store.get(t1.id()).isPresent());
            assertTrue(store.get(t2.id()).isEmpty());
        } finally {
            dir.toFile().deleteOnExit();
        }
    }

    @Test
    void templateStorePersistenceAcrossInstances() throws Exception {
        var dir = Files.createTempDirectory("vatn-templates-");
        try {
            var store1 = new JsonTemplateStore(dir);
            var saved = store1.save(new ContainerTemplate(null, "persist-test", "", null, "redis:7", null, null, null, null, null, null, null, null, null, null, null, 0, 0));

            var store2 = new JsonTemplateStore(dir);
            var loaded = store2.get(saved.id());
            assertTrue(loaded.isPresent());
            assertEquals("redis:7", loaded.get().image());
        } finally {
            dir.toFile().deleteOnExit();
        }
    }

    // ---- ContainerCreator ----

    @Test
    void containerCreatorRejectsMissingImage() {
        var ps = new MockProcessService();
        var creator = new ContainerCreator(ps, List.of());
        var result = creator.createFromTemplate(new ContainerTemplate(null, "bad", "", null, "", null, null, null, null, null, null, null, null, null, null, null, 0, 0));
        assertNull(result.containerId());
        assertNotNull(result.error());
    }

    @Test
    void containerCreatorCreatesPodmanContainer() {
        var ps = new MockProcessService()
                .withProbe("podman", new VProcessService.VProcessResult(0, "podman version 4.0", ""))
                .withExec(List.of("podman", "create", "--name", "web1", "-p", "8080:80", "-e", "ENV=prod", "nginx:latest"),
                        new VProcessService.VProcessResult(0, "abc123container\n", ""));
        var mgr = new GenericContainerManager(VContainerEngine.PODMAN, "podman", ps, new VJsonImpl());
        var creator = new ContainerCreator(ps, List.of(mgr));
        var result = creator.createFromTemplate(new ContainerTemplate(
                null, "web", "", "PODMAN",
                "nginx:latest", "web1", null, null,
                List.of("8080:80"), null, Map.of("ENV", "prod"),
                null, null, null, null, null, 0, 0));
        assertEquals("abc123container", result.containerId());
        assertNull(result.error());
    }

    @Test
    void containerCreatorRunsPostStartCommands() {
        var ps = new MockProcessService()
                .withExec(List.of("podman", "create", "--name", "web1", "nginx:latest"),
                        new VProcessService.VProcessResult(0, "container-id\n", ""))
                .withExec(List.of("podman", "exec", "container-id", "sh", "-c", "echo hello"),
                        new VProcessService.VProcessResult(0, "hello\n", ""));
        var mgr = new GenericContainerManager(VContainerEngine.PODMAN, "podman", ps, new VJsonImpl());
        var creator = new ContainerCreator(ps, List.of(mgr));
        var result = creator.createFromTemplate(new ContainerTemplate(
                null, "web", "", "PODMAN",
                "nginx:latest", "web1", null, null,
                null, null, Map.of(), null, null, null, null,
                List.of("echo hello"), 0, 0));
        assertNotNull(result.containerId());
        assertEquals(1, result.postStartResults().size());
        assertEquals(0, result.postStartResults().get(0).exitCode());
    }

    // ---- Mock Helpers ----

    private static VNodeContextImpl createContext() {
        var json = new VJsonImpl();
        var firewall = new VFirewallImpl();
        Map<Class<?>, Object> services = new HashMap<>();
        services.put(VJson.class, json);
        services.put(VProcessService.class, new MockProcessService()
                .withProbe("docker", new VProcessService.VProcessResult(0, "Docker version 24.0.0", ""))
                .withProbe("podman", new VProcessService.VProcessResult(1, "", "not found"))
                .withProbe("distrobox", new VProcessService.VProcessResult(0, "distrobox version 1.6.0", ""))
        );
        return new VNodeContextImpl("test-node", firewall, services);
    }

    private static class MockProcessService implements VProcessService {
        final Map<List<String>, VProcessService.VProcessResult> probeResults = new HashMap<>();
        final Map<List<String>, VProcessService.VProcessResult> execResults = new HashMap<>();
        final List<List<String>> executedCommands = Collections.synchronizedList(new ArrayList<>());

        MockProcessService withProbe(String binary, VProcessService.VProcessResult result) {
            probeResults.put(List.of(binary, "--version"), result);
            return this;
        }

        MockProcessService withExec(List<String> cmd, VProcessService.VProcessResult result) {
            execResults.put(cmd, result);
            return this;
        }

        @Override
        public VProcessService.VProcessResult probe(List<String> command) {
            var result = probeResults.get(command);
            return result != null ? result : new VProcessService.VProcessResult(1, "", "not found");
        }

        @Override
        public VProcessService.VProcessResult execute(List<String> command, Map<String, String> env, String workingDir) {
            executedCommands.add(command);
            var result = execResults.get(command);
            return result != null ? result : new VProcessService.VProcessResult(0, "", "");
        }

        @Override
        public VProcessService.VProcessResult execute(List<String> command, Map<String, String> env, String workingDir, VTrustLevel trustLevel) {
            return execute(command, env, workingDir);
        }

        @Override
        public VProcessService.VProcessHandle startAsync(List<String> command, Map<String, String> env, String workingDir) {
            executedCommands.add(command);
            return new VProcessService.VProcessHandle(0,
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream()
            );
        }
    }
}
