package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.VersionManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers version managers by reading their on-disk install trees — no subprocess calls.
 * Directory structure is the ground truth.
 */
public final class VersionManagerScanner {

    public List<VersionManager> scan() {
        var out = new ArrayList<VersionManager>();

        // mise: ~/.local/share/mise/installs/<tool>/<version>
        addIfPresent(out, "mise", ScannerUtil.homeDir(".local/share/mise/installs"), true);
        // sdkman: ~/.sdkman/candidates/<tool>/<version|current>
        addIfPresent(out, "sdkman", ScannerUtil.homeDir(".sdkman/candidates"), true);
        // asdf: ~/.asdf/installs/<tool>/<version>
        addIfPresent(out, "asdf", ScannerUtil.homeDir(".asdf/installs"), true);
        // nvm: ~/.nvm/versions/node/<version>  → single tool "node", subdirs are versions
        addIfPresent(out, "nvm", ScannerUtil.homeDir(".nvm/versions/node"), false);
        // pyenv: ~/.pyenv/versions/<version>
        addIfPresent(out, "pyenv", ScannerUtil.homeDir(".pyenv/versions"), false);
        // rbenv: ~/.rbenv/versions/<version>
        addIfPresent(out, "rbenv", ScannerUtil.homeDir(".rbenv/versions"), false);

        return List.copyOf(out);
    }

    /**
     * @param toolNamed when true, the immediate subdirs are tool names (mise/sdkman/asdf);
     *                  when false they are versions of a single implied tool (nvm/pyenv/rbenv).
     */
    private static void addIfPresent(List<VersionManager> out, String name, Path root, boolean toolNamed) {
        if (!ScannerUtil.isDir(root)) return;
        List<String> entries = ScannerUtil.subdirs(root).stream()
                .map(p -> p.getFileName().toString())
                .filter(n -> !n.startsWith("."))
                .toList();
        out.add(new VersionManager(name, root.toString(), entries));
    }
}
