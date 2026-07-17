package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.AcceleratorEntry;
import dev.vatn.plugins.devenv.model.McpEntry;
import dev.vatn.plugins.devenv.model.SimulatorEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M4ScannersTest {

    // -- McpScanner: keys-only, flat + nested shapes -------------------------------------

    @Test
    void parsesFlatMcpServersWithEnvKeysOnly() {
        Map<String, Object> root = Map.of("mcpServers", Map.of(
                "filesystem", Map.of(
                        "command", "npx",
                        "args", List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
                        "env", Map.of("API_KEY", "super-secret-value", "REGION", "us"))));

        List<McpEntry> e = McpScanner.fromConfigMap(root, "claude-desktop", "mcpServers");
        assertEquals(1, e.size());
        McpEntry m = e.get(0);
        assertEquals("filesystem", m.serverName());
        assertEquals("npx", m.command());
        assertEquals(3, m.args().size());
        assertEquals("claude-desktop", m.sourceApp());
        // env: names only, never values
        assertTrue(m.envKeys().containsAll(List.of("API_KEY", "REGION")));
        assertTrue(m.envKeys().stream().noneMatch(k -> k.contains("super-secret-value")));
    }

    @Test
    void parsesZedNestedContextServers() {
        Map<String, Object> root = Map.of("context_servers", Map.of(
                "my-server", Map.of("command", Map.of(
                        "path", "/usr/local/bin/mcp",
                        "args", List.of("--stdio"),
                        "env", Map.of("TOKEN", "x")))));

        List<McpEntry> e = McpScanner.fromConfigMap(root, "zed", "context_servers");
        assertEquals(1, e.size());
        assertEquals("/usr/local/bin/mcp", e.get(0).command());
        assertEquals(List.of("--stdio"), e.get(0).args());
        assertEquals(List.of("TOKEN"), e.get(0).envKeys());
    }

    @Test
    void missingServersKeyYieldsEmpty() {
        assertTrue(McpScanner.fromConfigMap(Map.of("other", 1), "cursor", "mcpServers").isEmpty());
    }

    // -- AcceleratorScanner --------------------------------------------------------------

    @Test
    void parsesNvidiaSmiCsv() {
        List<AcceleratorEntry> g = AcceleratorScanner.parseNvidiaSmi(
                "NVIDIA GeForce RTX 4090, 24564, 550.54.14\n");
        assertEquals(1, g.size());
        assertEquals("NVIDIA", g.get(0).vendor());
        assertEquals("CUDA", g.get(0).apiType());
        assertEquals(24564L * 1024 * 1024, g.get(0).memoryBytes());
        assertEquals("550.54.14", g.get(0).driver());
    }

    // -- AppleScanner parsers ------------------------------------------------------------

    @Test
    void parsesPkgVersionAndSimulators() {
        assertEquals("16.0.0.0.1.1724870958",
                AppleScanner.parsePkgVersion("package-id: com.apple.pkg.CLTools_Executables\nversion: 16.0.0.0.1.1724870958\n"));

        List<SimulatorEntry> sims = AppleScanner.parseSimulators(
                "-- iOS 17.5 --\n"
                        + "    iPhone 15 Pro (A1B2C3D4-1111-2222-3333-444455556666) (Shutdown)\n"
                        + "    iPhone 15 (11112222-3333-4444-5555-666677778888) (Booted)\n");
        assertEquals(2, sims.size());
        assertEquals("iPhone 15 Pro", sims.get(0).name());
        assertEquals("iOS 17.5", sims.get(0).runtime());
        assertEquals("Booted", sims.get(1).state());
    }
}
