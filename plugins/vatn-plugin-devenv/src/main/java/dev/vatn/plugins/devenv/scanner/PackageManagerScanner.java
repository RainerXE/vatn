package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.PackageEntry;
import dev.vatn.plugins.devenv.model.PackageInventory;
import dev.vatn.plugins.devenv.model.ServiceEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventories globally-installed packages. Homebrew and npm are read from the <em>filesystem</em>
 * (Cellar/Caskroom and global {@code node_modules}) so results are complete and independent of
 * the minimal subprocess environment; pip and brew-services remain best-effort subprocess calls.
 * Parsing/enumeration is split into static, unit-testable helpers.
 */
public final class PackageManagerScanner {

    private static final List<String> BREW_PREFIXES =
            List.of("/opt/homebrew", "/usr/local", "/home/linuxbrew/.linuxbrew");

    private final ScannerUtil util;

    public PackageManagerScanner(ScannerUtil util) {
        this.util = util;
    }

    public PackageInventory scan() {
        Path prefix = brewPrefix();
        List<PackageEntry> formulae = prefix != null
                ? parseInstallDir(prefix.resolve("Cellar"), "homebrew") : List.of();
        List<PackageEntry> casks = prefix != null
                ? parseInstallDir(prefix.resolve("Caskroom"), "homebrew-cask") : List.of();
        List<ServiceEntry> services = util.which("brew").isPresent()
                ? util.exec("brew", "services", "list").map(PackageManagerScanner::parseBrewServices).orElse(List.of())
                : List.of();

        List<PackageEntry> npm = scanNpmGlobals();

        String pip = util.which("pip3").isPresent() ? "pip3" : util.which("pip").isPresent() ? "pip" : null;
        List<PackageEntry> pipPkgs = pip != null
                ? util.exec(pip, "list", "--format=freeze").map(PackageManagerScanner::parsePipFreeze).orElse(List.of())
                : List.of();

        return new PackageInventory(formulae, casks, services, npm, pipPkgs);
    }

    // -- Homebrew (filesystem) ------------------------------------------------------------

    private static Path brewPrefix() {
        for (String p : BREW_PREFIXES) {
            Path cellar = Path.of(p, "Cellar");
            if (ScannerUtil.isDir(cellar)) return Path.of(p);
        }
        return null;
    }

    /** Each subdir of an install root is a package; its newest version subdir is the version. */
    static List<PackageEntry> parseInstallDir(Path root, String source) {
        var out = new ArrayList<PackageEntry>();
        for (Path pkg : ScannerUtil.subdirs(root)) {
            List<Path> versions = ScannerUtil.subdirs(pkg);
            String version = versions.isEmpty() ? ""
                    : versions.get(versions.size() - 1).getFileName().toString();
            out.add(new PackageEntry(pkg.getFileName().toString(), version, source));
        }
        return List.copyOf(out);
    }

    // -- npm globals (filesystem, across all roots) ---------------------------------------

    private List<PackageEntry> scanNpmGlobals() {
        var roots = new ArrayList<Path>();
        // active node's global root, if npm resolves under the minimal env
        util.exec("npm", "root", "-g").map(String::trim).filter(s -> !s.isBlank())
                .ifPresent(r -> roots.add(Path.of(r)));
        // known global roots across version managers / package managers
        for (Path nodeVer : ScannerUtil.subdirs(ScannerUtil.homeDir(".nvm/versions/node"))) {
            roots.add(nodeVer.resolve("lib/node_modules"));
        }
        roots.add(Path.of("/opt/homebrew/lib/node_modules"));
        roots.add(Path.of("/usr/local/lib/node_modules"));
        roots.add(ScannerUtil.homeDir(".npm-global/lib/node_modules"));
        roots.add(ScannerUtil.homeDir(".local/lib/node_modules"));

        var byName = new LinkedHashMap<String, PackageEntry>();
        for (Path nm : roots) {
            for (PackageEntry e : collectNpm(nm)) byName.putIfAbsent(e.name(), e);
        }
        return List.copyOf(byName.values());
    }

    /** Enumerate packages in a global {@code node_modules} dir (handling {@code @scope/pkg}). */
    static List<PackageEntry> collectNpm(Path nodeModules) {
        var out = new ArrayList<PackageEntry>();
        for (Path entry : ScannerUtil.subdirs(nodeModules)) {
            String dn = entry.getFileName().toString();
            if (dn.equals(".bin") || dn.equals(".cache")) continue;
            if (dn.startsWith("@")) { // scope dir → its children are packages
                for (Path scoped : ScannerUtil.subdirs(entry)) {
                    out.add(new PackageEntry(dn + "/" + scoped.getFileName(), readPkgVersion(scoped), "npm"));
                }
            } else if (!dn.startsWith(".")) {
                out.add(new PackageEntry(dn, readPkgVersion(entry), "npm"));
            }
        }
        return List.copyOf(out);
    }

    /** Cheap {@code "version"} read from a package's package.json (first quoted value). */
    static String readPkgVersion(Path pkgDir) {
        for (String line : ScannerUtil.readLines(pkgDir.resolve("package.json"))) {
            int i = line.indexOf("\"version\"");
            if (i < 0) continue;
            int colon = line.indexOf(':', i);
            if (colon < 0) continue;
            int q1 = line.indexOf('"', colon + 1);
            int q2 = q1 >= 0 ? line.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) return line.substring(q1 + 1, q2);
        }
        return "";
    }

    // -- best-effort subprocess parsers (services, pip) -----------------------------------

    /** {@code brew services list}: header then "name status [user] [file]". */
    static List<ServiceEntry> parseBrewServices(String output) {
        var out = new ArrayList<ServiceEntry>();
        for (String line : output.lines().toList()) {
            String l = line.strip();
            if (l.isEmpty() || l.toLowerCase().startsWith("name ")) continue;
            String[] parts = l.split("\\s+");
            String status = parts.length > 1 ? parts[1] : "unknown";
            String user = parts.length > 2 && !parts[2].startsWith("/") ? parts[2] : "";
            out.add(new ServiceEntry(parts[0], status, user));
        }
        return List.copyOf(out);
    }

    /** {@code pip list --format=freeze}: "name==version" (or "name @ file://…"). */
    static List<PackageEntry> parsePipFreeze(String output) {
        var out = new ArrayList<PackageEntry>();
        for (String line : output.lines().toList()) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int eq = l.indexOf("==");
            if (eq > 0) {
                out.add(new PackageEntry(l.substring(0, eq), l.substring(eq + 2), "pip"));
            } else {
                int at = l.indexOf(" @ ");
                if (at > 0) out.add(new PackageEntry(l.substring(0, at).strip(), "", "pip"));
            }
        }
        return List.copyOf(out);
    }
}
