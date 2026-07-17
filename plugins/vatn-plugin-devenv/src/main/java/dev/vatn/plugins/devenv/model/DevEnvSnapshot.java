package dev.vatn.plugins.devenv.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable inventory of the local developer environment.
 *
 * <p>Populated incrementally by milestone: M1 runtimes/compilers; M2 version & package
 * managers. Later milestones add venvs, containers, kubernetes, agents, MCP servers, local
 * LLM engines/models, accelerators, and the Apple layer. {@code schemaVersion} is carried so
 * persisted snapshots and diffs survive model changes.
 *
 * @param scannedAt ISO-8601 instant the scan completed (stored as text for portable JSON)
 */
public record DevEnvSnapshot(
        int schemaVersion,
        String scannedAt,
        String hostname,
        String platform,
        List<RuntimeEntry> runtimes,
        List<RuntimeEntry> compilers,
        List<JvmInstall> jvms,
        List<RuntimeInstall> languageInstalls,
        List<VersionManager> versionManagers,
        PackageInventory packages,
        List<VenvEntry> venvs,
        ContainerInventory containers,
        KubernetesInfo kubernetes,
        List<AgentEntry> codingAgents,
        List<McpEntry> mcpServers,
        LlmInventory llm,
        List<AcceleratorEntry> accelerators,
        AppleInfo apple) {

    /** Current snapshot schema version. Bump on any breaking model change. */
    public static final int SCHEMA_VERSION = 1;

    /** A safe, empty snapshot for null-free handling before the first scan completes. */
    public static DevEnvSnapshot empty() {
        return new DevEnvSnapshot(SCHEMA_VERSION, Instant.EPOCH.toString(), "", platformString(),
                List.of(), List.of(), List.of(), List.of(), List.of(), PackageInventory.empty(),
                List.of(), ContainerInventory.empty(), KubernetesInfo.empty(),
                List.of(), List.of(), LlmInventory.empty(), List.of(), null);
    }

    /** Runtimes + compilers + JVMs + language installs + version managers, for {@code GET /devenv/runtimes}. */
    public Map<String, Object> runtimesSlice() {
        return Map.of("runtimes", runtimes, "compilers", compilers, "jvms", jvms,
                "languageInstalls", languageInstalls, "versionManagers", versionManagers);
    }

    /** Coding agents + MCP servers, for {@code GET /devenv/agents}. */
    public Map<String, Object> agentsSlice() {
        return Map.of("codingAgents", codingAgents, "mcpServers", mcpServers);
    }

    public int totalRuntimeCount() {
        return runtimes.size() + compilers.size();
    }

    /** {@code os.name/os.arch} — a stable, cheap platform label. */
    public static String platformString() {
        return System.getProperty("os.name", "?") + "/" + System.getProperty("os.arch", "?");
    }
}
