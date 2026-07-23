package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.AgentEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects AI coding agents and editors/IDEs by probing CLI binaries on PATH and known macOS
 * app bundles. Read-only.
 */
public final class AgentScanner {

    private record CliSpec(String binary, String type) {}

    private static final List<CliSpec> CLIS = List.of(
            new CliSpec("claude", "CLI"),
            new CliSpec("aider", "CLI"),
            new CliSpec("gemini", "CLI"),
            new CliSpec("cursor-agent", "CLI"),
            new CliSpec("amp", "CLI"),
            new CliSpec("cline", "CLI"),
            new CliSpec("code", "IDE"),      // VS Code CLI
            new CliSpec("codium", "IDE"),
            new CliSpec("zed", "IDE"),
            new CliSpec("nvim", "IDE"));

    // macOS app bundles (name → .app)
    private static final List<String> MAC_APPS = List.of(
            "Cursor", "Windsurf", "Claude", "Zed", "Visual Studio Code");

    private final ScannerUtil util;

    public AgentScanner(ScannerUtil util) {
        this.util = util;
    }

    public List<AgentEntry> scan() {
        var out = new ArrayList<AgentEntry>();
        for (CliSpec spec : CLIS) {
            util.which(spec.binary()).ifPresent(path -> {
                String version = util.exec(spec.binary(), "--version").map(ScannerUtil::extractVersion).orElse("");
                out.add(new AgentEntry(spec.binary(), version, path, "", spec.type()));
            });
        }
        if (ScannerUtil.isMacOS()) {
            for (String app : MAC_APPS) {
                Path bundle = Path.of("/Applications/" + app + ".app");
                if (ScannerUtil.isDir(bundle)
                        && out.stream().noneMatch(a -> a.name().equalsIgnoreCase(app))) {
                    out.add(new AgentEntry(app, "", bundle.toString(), "", "GUI"));
                }
            }
        }
        return List.copyOf(out);
    }
}
