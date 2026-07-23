package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code vatn inventory} — shows everything VATN has installed on this machine.
 *
 * <p>Scans the VATN home directory live and cross-references the latest install log
 * so the user can always find where each component was placed.
 *
 * <pre>
 *   vatn inventory            # pretty table, all sections
 *   vatn inventory --json     # machine-readable JSON
 *   vatn inventory --section webadmin
 *   vatn inventory --logs     # list all install log files
 * </pre>
 */
@Command(
    name        = "inventory",
    description = "Show what is installed on this machine — locations, versions, and service status.",
    mixinStandardHelpOptions = true
)
public class InventoryCommand implements Callable<Integer> {

    @Option(names = {"--home"},
            description = "VATN home directory (default: ~/.vatn or $VATN_HOME).")
    private Path home;

    @Option(names = {"--json"},
            description = "Output as JSON instead of a human-readable table.")
    private boolean json;

    @Option(names = {"--section"},
            description = "Show only one section: core | webadmin | plugins | examples | logs | path")
    private String section;

    @Option(names = {"--logs"},
            description = "List all install log files found in VATN logs dir.")
    private boolean listLogs;

    // ── ANSI colours (auto-disabled when not a TTY) ────────────────────────
    private static final boolean COLOUR = System.console() != null;
    private static final String C  = COLOUR ? "\u001B[0;36m" : "";   // cyan
    private static final String G  = COLOUR ? "\u001B[0;32m" : "";   // green
    private static final String Y  = COLOUR ? "\u001B[1;33m" : "";   // yellow
    private static final String D  = COLOUR ? "\u001B[2m"    : "";   // dim
    private static final String B  = COLOUR ? "\u001B[1m"    : "";   // bold
    private static final String R  = COLOUR ? "\u001B[0m"    : "";   // reset
    private static final String X  = COLOUR ? "\u001B[0;31m" : "";   // red

    private static final DateTimeFormatter DT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public Integer call() {
        Path vatnHome = resolveHome();

        if (json) {
            return printJson(vatnHome);
        }

        if (listLogs) {
            return printLogFiles(vatnHome);
        }

        banner();

        boolean all = (section == null);

        if (all || "core".equalsIgnoreCase(section))     printCore(vatnHome);
        if (all || "webadmin".equalsIgnoreCase(section)) printWebAdmin(vatnHome);
        if (all || "plugins".equalsIgnoreCase(section))  printPlugins(vatnHome);
        if (all || "examples".equalsIgnoreCase(section)) printExamples(vatnHome);
        if (all || "path".equalsIgnoreCase(section))     printPath(vatnHome);
        if (all || "logs".equalsIgnoreCase(section))     printLatestLog(vatnHome);

        return 0;
    }

    // ── Sections ─────────────────────────────────────────────────────────────

    private void printCore(Path home) {
        section("VATN Core Runtime");
        row("Home",         home.toString(),                       exists(home));
        row("CLI launcher", home.resolve("bin/vatn").toString(),   exists(home.resolve("bin/vatn")));
        row("Runtime JAR",  home.resolve("lib/vatn-cli.jar").toString(),
                                                                   exists(home.resolve("lib/vatn-cli.jar")));
        row("Config",       home.resolve("config/vatn.conf").toString(),
                                                                   exists(home.resolve("config/vatn.conf")));
        row("Logs dir",     home.resolve("logs").toString(),       exists(home.resolve("logs")));

        // Java version
        try {
            Process p = new ProcessBuilder("java", "-version")
                .redirectErrorStream(true).start();
            String ver = new String(p.getInputStream().readAllBytes()).trim();
            if (ver.isBlank()) ver = System.getProperty("java.version", "unknown");
            row("Java",     ver, true);
        } catch (IOException ignored) {
            row("Java", System.getProperty("java.version", "unknown"), true);
        }

        // CLI JAR size
        Path jar = home.resolve("lib/vatn-cli.jar");
        if (Files.exists(jar)) {
            try { row("JAR size", formatSize(Files.size(jar)), true); }
            catch (IOException ignored) {}
        }
        println();
    }

    private void printWebAdmin(Path home) {
        section("VATN Web Admin");
        Path bin  = home.resolve("bin/vatn-webadmin");
        Path jar  = home.resolve("lib/vatn-webadmin.jar");
        Path out  = home.resolve("logs/webadmin.out.log");
        Path err  = home.resolve("logs/webadmin.err.log");

        row("Binary",    bin.toString(),  exists(bin));
        row("JAR",       jar.toString(),  exists(jar));
        row("Log (out)", out.toString(),  exists(out));
        row("Log (err)", err.toString(),  exists(err));
        row("Admin URL", "http://localhost:9108/vatn/admin",      true);
        row("Containers","http://localhost:9108/vatn/containers",  true);

        // Detect OS and service registration
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            Path plist = Path.of(System.getProperty("user.home"),
                "Library/LaunchAgents/dev.vatn.webadmin.plist");
            row("LaunchAgent", plist.toString(), exists(plist));
            if (Files.exists(plist)) {
                String status = runQuiet("launchctl", "list", "dev.vatn.webadmin");
                row("Service status", status.isBlank() ? "not loaded" : "loaded ✔", !status.isBlank());
            }
        } else if (os.contains("linux")) {
            Path svc = Path.of(System.getProperty("user.home"),
                ".config/systemd/user/vatn-webadmin.service");
            row("Systemd unit", svc.toString(), exists(svc));
            if (Files.exists(svc)) {
                String status = runQuiet("systemctl", "--user", "is-active", "vatn-webadmin");
                row("Service status", status.isBlank() ? "unknown" : status, "active".equals(status.strip()));
            }
        }
        println();
    }

    private void printPlugins(Path home) {
        section("Plugins");
        Path pluginsDir = home.resolve("plugins");
        row("Plugins dir", pluginsDir.toString(), exists(pluginsDir));

        if (!Files.isDirectory(pluginsDir)) {
            println("  " + D + "(no plugins directory found)" + R);
            println();
            return;
        }

        List<Path> jars = new ArrayList<>();
        try (Stream<Path> s = Files.list(pluginsDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar"))
             .sorted()
             .forEach(jars::add);
        } catch (IOException ignored) {}

        if (jars.isEmpty()) {
            println("  " + D + "(no plugin JARs installed)" + R);
        } else {
            println("  " + B + String.format("%-45s  %8s  %s", "Plugin", "Size", "Modified") + R);
            println("  " + D + "─".repeat(72) + R);
            for (Path jar : jars) {
                try {
                    BasicFileAttributes attr = Files.readAttributes(jar, BasicFileAttributes.class);
                    String name = jar.getFileName().toString();
                    String size = formatSize(attr.size());
                    String mod  = DT.format(attr.lastModifiedTime().toInstant());
                    println("  " + G + "✔" + R + "  " + String.format("%-43s  %8s  %s", name, size, mod));
                } catch (IOException e) {
                    println("  " + G + "✔" + R + "  " + jar.getFileName());
                }
            }
            println();
            println("  " + D + "Total: " + jars.size() + " plugin(s) in " + pluginsDir + R);
        }
        println();
    }

    private void printExamples(Path home) {
        section("Examples");
        // Check a few common dev locations for a vatn clone with examples/
        List<Path> candidates = List.of(
            Path.of(System.getProperty("user.home"), "Development", "vatn", "examples"),
            Path.of(System.getProperty("user.home"), "Projects",    "vatn", "examples"),
            Path.of(System.getProperty("user.home"), "dev",         "vatn", "examples"),
            Path.of(System.getProperty("user.home"), "code",        "vatn", "examples")
        );
        Path found = null;
        for (Path c : candidates) {
            if (Files.isDirectory(c)) { found = c; break; }
        }

        if (found == null) {
            println("  " + D + "(examples not found — run: git clone https://github.com/RainerXE/vatn.git)" + R);
        } else {
            row("Examples dir", found.toString(), true);
            try (Stream<Path> s = Files.list(found)) {
                s.filter(Files::isDirectory)
                 .sorted()
                 .map(p -> p.getFileName().toString())
                 .forEach(name -> println("  " + D + "  " + name + R));
            } catch (IOException ignored) {}
        }
        println();
    }

    private void printPath(Path home) {
        section("PATH");
        String pathEnv = System.getenv("PATH");
        String binDir  = home.resolve("bin").toString();
        boolean onPath = pathEnv != null && Arrays.asList(pathEnv.split(":")).contains(binDir);
        row("bin on PATH", binDir, onPath);
        if (!onPath) {
            println("  " + Y + "  ⚠  Add to PATH:  export PATH=\"" + binDir + ":$PATH\"" + R);
        }
        println();
    }

    private void printLatestLog(Path home) {
        section("Install Logs");
        Path logsDir = home.resolve("logs");
        if (!Files.isDirectory(logsDir)) {
            println("  " + D + "(no logs directory)" + R);
            println();
            return;
        }

        List<Path> logs = new ArrayList<>();
        try (Stream<Path> s = Files.list(logsDir)) {
            s.filter(p -> p.getFileName().toString().startsWith("install-") &&
                          p.getFileName().toString().endsWith(".log"))
             .sorted(Comparator.reverseOrder())
             .forEach(logs::add);
        } catch (IOException ignored) {}

        if (logs.isEmpty()) {
            println("  " + D + "(no install logs found)" + R);
        } else {
            row("Latest log", logs.get(0).toString(), true);
            println("  " + D + "  View: cat " + logs.get(0) + R);
            if (logs.size() > 1) {
                println("  " + D + "  Older logs (" + (logs.size() - 1) + "): " + logsDir + "/install-*.log" + R);
            }
        }
        println();
    }

    private int printLogFiles(Path home) {
        Path logsDir = home.resolve("logs");
        println(B + "Install logs in " + logsDir + R);
        println();
        if (!Files.isDirectory(logsDir)) {
            println(D + "  (logs directory not found)" + R);
            return 0;
        }
        try (Stream<Path> s = Files.list(logsDir)) {
            s.filter(p -> p.getFileName().toString().startsWith("install-"))
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try {
                     long size = Files.size(p);
                     Instant mod = Files.getLastModifiedTime(p).toInstant();
                     System.out.printf("  %-50s  %8s  %s%n",
                         p.getFileName(), formatSize(size), DT.format(mod));
                 } catch (IOException e) {
                     System.out.println("  " + p.getFileName());
                 }
             });
        } catch (IOException e) {
            System.err.println("Error reading logs: " + e.getMessage());
            return 1;
        }
        println();
        return 0;
    }

    private int printJson(Path home) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"vatn_home\": ").append(q(home.toString())).append(",\n");
        sb.append("  \"core\": {\n");
        sb.append("    \"launcher\": ").append(q(home.resolve("bin/vatn").toString())).append(",\n");
        sb.append("    \"launcher_exists\": ").append(exists(home.resolve("bin/vatn"))).append(",\n");
        sb.append("    \"runtime_jar\": ").append(q(home.resolve("lib/vatn-cli.jar").toString())).append(",\n");
        sb.append("    \"runtime_jar_exists\": ").append(exists(home.resolve("lib/vatn-cli.jar"))).append(",\n");
        sb.append("    \"config\": ").append(q(home.resolve("config/vatn.conf").toString())).append(",\n");
        sb.append("    \"java_version\": ").append(q(System.getProperty("java.version", "unknown"))).append("\n");
        sb.append("  },\n");
        sb.append("  \"webadmin\": {\n");
        sb.append("    \"binary\": ").append(q(home.resolve("bin/vatn-webadmin").toString())).append(",\n");
        sb.append("    \"binary_exists\": ").append(exists(home.resolve("bin/vatn-webadmin"))).append(",\n");
        sb.append("    \"jar\": ").append(q(home.resolve("lib/vatn-webadmin.jar").toString())).append(",\n");
        sb.append("    \"jar_exists\": ").append(exists(home.resolve("lib/vatn-webadmin.jar"))).append(",\n");
        sb.append("    \"log_out\": ").append(q(home.resolve("logs/webadmin.out.log").toString())).append(",\n");
        sb.append("    \"admin_url\": \"http://localhost:9108/vatn/admin\",\n");
        sb.append("    \"containers_url\": \"http://localhost:9108/vatn/containers\"\n");
        sb.append("  },\n");

        // plugins
        sb.append("  \"plugins\": [\n");
        Path pluginsDir = home.resolve("plugins");
        List<String> jarNames = new ArrayList<>();
        if (Files.isDirectory(pluginsDir)) {
            try (Stream<Path> s = Files.list(pluginsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jar"))
                 .sorted()
                 .forEach(p -> jarNames.add(p.toString()));
            } catch (IOException ignored) {}
        }
        for (int i = 0; i < jarNames.size(); i++) {
            sb.append("    ").append(q(jarNames.get(i)));
            if (i < jarNames.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // install logs
        sb.append("  \"install_logs\": [\n");
        Path logsDir = home.resolve("logs");
        List<String> logPaths = new ArrayList<>();
        if (Files.isDirectory(logsDir)) {
            try (Stream<Path> s = Files.list(logsDir)) {
                s.filter(p -> p.getFileName().toString().startsWith("install-"))
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> logPaths.add(p.toString()));
            } catch (IOException ignored) {}
        }
        for (int i = 0; i < logPaths.size(); i++) {
            sb.append("    ").append(q(logPaths.get(i)));
            if (i < logPaths.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        System.out.print(sb);
        return 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Path resolveHome() {
        if (home != null) return home;
        String env = System.getenv("VATN_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".vatn");
    }

    private void banner() {
        println();
        println(B + C + "  ⬡  VATN Inventory" + R + D + "  — what's installed on this machine" + R);
        println(D + "  " + "─".repeat(54) + R);
        println();
    }

    private void section(String title) {
        println(B + "  " + title + R);
        println(D + "  " + "─".repeat(50) + R);
    }

    private void row(String label, String value, boolean present) {
        String icon  = present ? G + "✔" + R : X + "✖" + R;
        String color = present ? C : D;
        System.out.printf("  %s  %-18s %s%s%s%n", icon, label, color, value, R);
    }

    private void println()          { System.out.println(); }
    private void println(String s)  { System.out.println(s); }

    private boolean exists(Path p)  { return Files.exists(p); }

    private String formatSize(long bytes) {
        if (bytes < 1024)            return bytes + " B";
        if (bytes < 1024 * 1024)     return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /** Run a command silently; return trimmed stdout or empty string on failure. */
    private String runQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
