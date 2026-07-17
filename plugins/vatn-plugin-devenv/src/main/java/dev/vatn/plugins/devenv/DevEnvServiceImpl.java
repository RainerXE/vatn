package dev.vatn.plugins.devenv;

import dev.vatn.api.VJson;
import dev.vatn.plugins.devenv.model.AcceleratorEntry;
import dev.vatn.plugins.devenv.model.AgentEntry;
import dev.vatn.plugins.devenv.model.AppleInfo;
import dev.vatn.plugins.devenv.model.ContainerInventory;
import dev.vatn.plugins.devenv.model.DevEnvSnapshot;
import dev.vatn.plugins.devenv.model.JvmInstall;
import dev.vatn.plugins.devenv.model.KubernetesInfo;
import dev.vatn.plugins.devenv.model.LlmInventory;
import dev.vatn.plugins.devenv.model.McpEntry;
import dev.vatn.plugins.devenv.model.PackageInventory;
import dev.vatn.plugins.devenv.model.RuntimeEntry;
import dev.vatn.plugins.devenv.model.RuntimeInstall;
import dev.vatn.plugins.devenv.model.VenvEntry;
import dev.vatn.plugins.devenv.model.VersionManager;
import dev.vatn.plugins.devenv.scanner.AcceleratorScanner;
import dev.vatn.plugins.devenv.scanner.AgentScanner;
import dev.vatn.plugins.devenv.scanner.AppleScanner;
import dev.vatn.plugins.devenv.scanner.ContainerScanner;
import dev.vatn.plugins.devenv.scanner.JvmScanner;
import dev.vatn.plugins.devenv.scanner.KubernetesScanner;
import dev.vatn.plugins.devenv.scanner.LanguageRuntimeScanner;
import dev.vatn.plugins.devenv.scanner.LlmScanner;
import dev.vatn.plugins.devenv.scanner.McpScanner;
import dev.vatn.plugins.devenv.scanner.PackageManagerScanner;
import dev.vatn.plugins.devenv.scanner.RuntimeScanner;
import dev.vatn.plugins.devenv.scanner.ScannerUtil;
import dev.vatn.plugins.devenv.scanner.VenvScanner;
import dev.vatn.plugins.devenv.scanner.VersionManagerScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the scanner modules in parallel under a global wall-clock deadline. Each module
 * runs on its own virtual thread; a module that fails or overruns yields an empty result and
 * never blocks the others (DCN-DEV-02/03).
 */
public final class DevEnvServiceImpl implements DevEnvService {

    private static final Logger log = LoggerFactory.getLogger(DevEnvServiceImpl.class);

    private final DevEnvConfig config;
    private final RuntimeScanner runtimeScanner;
    private final JvmScanner jvmScanner;
    private final LanguageRuntimeScanner languageRuntimeScanner;
    private final VersionManagerScanner versionManagerScanner;
    private final PackageManagerScanner packageManagerScanner;
    private final VenvScanner venvScanner;
    private final ContainerScanner containerScanner;
    private final KubernetesScanner kubernetesScanner;
    private final AgentScanner agentScanner;
    private final McpScanner mcpScanner;
    private final LlmScanner llmScanner;
    private final AppleScanner appleScanner;
    private final AcceleratorScanner acceleratorScanner;

    private final AtomicReference<DevEnvSnapshot> last = new AtomicReference<>();
    private ScheduledExecutorService scheduler;

    public DevEnvServiceImpl(DevEnvConfig config, ScannerUtil util, VJson json) {
        this.config = config;
        this.runtimeScanner = new RuntimeScanner(util);
        this.jvmScanner = new JvmScanner();
        this.languageRuntimeScanner = new LanguageRuntimeScanner(util);
        this.versionManagerScanner = new VersionManagerScanner();
        this.packageManagerScanner = new PackageManagerScanner(util);
        this.venvScanner = new VenvScanner(util);
        this.containerScanner = new ContainerScanner(util);
        this.kubernetesScanner = new KubernetesScanner(util);
        this.agentScanner = new AgentScanner(util);
        this.mcpScanner = new McpScanner(json);
        this.llmScanner = new LlmScanner(util, json);
        this.appleScanner = new AppleScanner(util);
        this.acceleratorScanner = new AcceleratorScanner(util);
    }

    // -- lifecycle ------------------------------------------------------------------------

    public void start() {
        if (config.isScanOnStartup()) {
            Thread.ofVirtual().name("devenv-startup-scan").start(this::scanQuietly);
        }
        if (!config.getRefreshInterval().isZero()) {
            long ms = config.getRefreshInterval().toMillis();
            scheduler = new ScheduledThreadPoolExecutor(1,
                    r -> Thread.ofVirtual().name("devenv-refresh").unstarted(r));
            scheduler.scheduleAtFixedRate(this::scanQuietly, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // -- DevEnvService --------------------------------------------------------------------

    @Override
    public DevEnvSnapshot scan() {
        long started = System.currentTimeMillis();

        // Ordered map of named scanner modules → results, keyed so assembly is order-free.
        Map<String, Callable<Object>> tasks = new LinkedHashMap<>();
        tasks.put("runtimes",        () -> runtimeScanner.scanRuntimes());
        tasks.put("compilers",       () -> runtimeScanner.scanCompilers());
        tasks.put("jvms",            () -> jvmScanner.scan());
        tasks.put("languageInstalls",() -> languageRuntimeScanner.scan());
        tasks.put("versionManagers", () -> versionManagerScanner.scan());
        tasks.put("packages",        () -> packageManagerScanner.scan());
        tasks.put("venvs",           () -> venvScanner.scan());
        tasks.put("containers",      () -> containerScanner.scan());
        tasks.put("kubernetes",      () -> kubernetesScanner.scan());
        tasks.put("agents",          () -> agentScanner.scan());
        tasks.put("mcp",             () -> mcpScanner.scan());
        tasks.put("llm",             () -> llmScanner.scan());
        tasks.put("accelerators",    () -> acceleratorScanner.scan());
        tasks.put("apple",           () -> appleScanner.scan());

        Map<String, Object> r = runAll(tasks);

        DevEnvSnapshot snapshot = new DevEnvSnapshot(
                DevEnvSnapshot.SCHEMA_VERSION,
                Instant.now().toString(),
                hostname(),
                DevEnvSnapshot.platformString(),
                entries(r.get("runtimes")),
                entries(r.get("compilers")),
                jvms(r.get("jvms")),
                installs(r.get("languageInstalls")),
                vms(r.get("versionManagers")),
                r.get("packages") instanceof PackageInventory pi ? pi : PackageInventory.empty(),
                venvs(r.get("venvs")),
                r.get("containers") instanceof ContainerInventory ci ? ci : ContainerInventory.empty(),
                r.get("kubernetes") instanceof KubernetesInfo ki ? ki : KubernetesInfo.empty(),
                agents(r.get("agents")),
                mcps(r.get("mcp")),
                r.get("llm") instanceof LlmInventory inv ? inv : LlmInventory.empty(),
                accelerators(r.get("accelerators")),
                r.get("apple") instanceof AppleInfo ai ? ai : null);

        last.set(snapshot);
        log.info("DevEnv scan completed in {}ms — {} runtimes, {} JVMs, {} lang-installs, {} brew, {} npm, {} agents, {} mcp, {} llm-engines/{} models",
                System.currentTimeMillis() - started, snapshot.runtimes().size(),
                snapshot.jvms().size(), snapshot.languageInstalls().size(),
                snapshot.packages().brewFormulae().size(), snapshot.packages().npmGlobals().size(),
                snapshot.codingAgents().size(), snapshot.mcpServers().size(),
                snapshot.llm().engines().size(), snapshot.llm().models().size());
        return snapshot;
    }

    @Override
    public Optional<DevEnvSnapshot> lastSnapshot() {
        return Optional.ofNullable(last.get());
    }

    @Override
    public void refresh() {
        Thread.ofVirtual().name("devenv-manual-refresh").start(this::scanQuietly);
    }

    // -- orchestration helpers ------------------------------------------------------------

    private void scanQuietly() {
        try { scan(); }
        catch (Exception e) { log.warn("DevEnv scan failed", e); }
    }

    /** Run all tasks in parallel under the global scan deadline; missing/failed → null. */
    private Map<String, Object> runAll(Map<String, Callable<Object>> tasks) {
        var names = List.copyOf(tasks.keySet());
        var out = new LinkedHashMap<String, Object>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Object>> guarded = names.stream()
                    .map(n -> guarded(n, tasks.get(n)))
                    .toList();
            List<Future<Object>> futures =
                    exec.invokeAll(guarded, config.getScanTimeout().toMillis(), TimeUnit.MILLISECONDS);
            for (int i = 0; i < names.size(); i++) {
                out.put(names.get(i), resultOf(names.get(i), futures.get(i)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private Callable<Object> guarded(String name, Callable<Object> task) {
        return () -> {
            if (config.shouldSkip(name)) return null;
            try {
                return task.call();
            } catch (Exception e) {
                log.debug("scanner [{}] failed: {}", name, e.toString());
                return null;
            }
        };
    }

    private Object resultOf(String name, Future<Object> f) {
        try {
            return f.get();
        } catch (Exception e) { // CancellationException on timeout, ExecutionException, etc.
            log.debug("scanner [{}] did not complete: {}", name, e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RuntimeEntry> entries(Object o) {
        return o instanceof List<?> list ? (List<RuntimeEntry>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<VersionManager> vms(Object o) {
        return o instanceof List<?> list ? (List<VersionManager>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<JvmInstall> jvms(Object o) {
        return o instanceof List<?> list ? (List<JvmInstall>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<RuntimeInstall> installs(Object o) {
        return o instanceof List<?> list ? (List<RuntimeInstall>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<VenvEntry> venvs(Object o) {
        return o instanceof List<?> list ? (List<VenvEntry>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<AgentEntry> agents(Object o) {
        return o instanceof List<?> list ? (List<AgentEntry>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<McpEntry> mcps(Object o) {
        return o instanceof List<?> list ? (List<McpEntry>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<AcceleratorEntry> accelerators(Object o) {
        return o instanceof List<?> list ? (List<AcceleratorEntry>) list : List.of();
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return ""; }
    }
}
