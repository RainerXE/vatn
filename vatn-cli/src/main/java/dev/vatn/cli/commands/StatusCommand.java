package dev.vatn.cli.commands;

import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(name = "status",
         mixinStandardHelpOptions = true,
         description = "Show VATN installation and service status.")
public class StatusCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("VATN Status");
        System.out.println("  Version:       " + version());
        System.out.println("  Mode:          " + (isJar() ? "JAR" : "Native"));
        System.out.println("  VATN_HOME:     " + vatnHome());
        System.out.println("  Java:          " + javaVersion());
        System.out.println("  Platform:      " + os() + " (" + arch() + ")");

        // CLI binary
        Path cliJar = vatnHome().resolve("lib/vatn-cli.jar");
        System.out.println("  CLI JAR:       " + cliJar + "  " + (Files.exists(cliJar) ? exists() : missing()));

        // WebAdmin
        Path webJar = vatnHome().resolve("lib/vatn-webadmin.jar");
        Path webBin = vatnHome().resolve("bin/vatn-webadmin");
        System.out.println("  WebAdmin JAR:  " + webJar + "  " + (Files.exists(webJar) ? exists() : missing()));
        if (Files.exists(webBin)) {
            System.out.println("  WebAdmin Bin:  " + webBin + "  " + exists());
        }

        // Plugins
        Path pluginsDir = vatnHome().resolve("plugins");
        int pluginCount = 0;
        if (Files.isDirectory(pluginsDir)) {
            try (var stream = Files.list(pluginsDir)) {
                pluginCount = (int) stream.filter(p -> p.toString().endsWith(".jar")).count();
            } catch (IOException e) {
                // ignore
            }
        }
        System.out.println("  Plugins:       " + pluginCount + " installed");

        // WebAdmin process check
        System.out.println();
        System.out.println("  WebAdmin process:");
        int port = probeWebAdminPort();
        if (port > 0) {
            System.out.println("    Running on port " + port);
        } else {
            System.out.println("    Not detected");
        }
        if (isLinux()) {
            String scope = isRoot() ? "" : "--user";
            String active = runQuiet("systemctl", scope, "is-active", "vatn-webadmin");
            if (!active.isBlank()) System.out.println("    systemd:     " + active);
        } else if (isMac()) {
            Path plist = Path.of(System.getProperty("user.home"),
                "Library/LaunchAgents/dev.vatn.webadmin.plist");
            if (Files.exists(plist)) {
                String out = runQuiet("launchctl", "list", "dev.vatn.webadmin");
                System.out.println("    launchd:     " + (out.isBlank() ? "not loaded" : "loaded"));
            }
        }

        // Check if VATN_ADMIN_TOKEN is set
        String token = System.getenv("VATN_ADMIN_TOKEN");
        System.out.println();
        System.out.println("  Admin auth:    " + (token != null ? "enabled (VATN_ADMIN_TOKEN set)" : "disabled (open access)"));

        return 0;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String version() {
        Properties props = new Properties();
        try (InputStream is = StatusCommand.class.getResourceAsStream("/vatn/version.properties")) {
            if (is != null) { props.load(is); return props.getProperty("version", "?"); }
        } catch (Exception e) { /* fall through */ }
        return "?";
    }

    private static boolean isJar() {
        String cmd = ProcessHandle.current().info().command().orElse("");
        return cmd.endsWith(".jar") || cmd.contains("java");
    }

    private static String javaVersion() {
        return System.getProperty("java.version", "?");
    }

    private static String os() {
        return System.getProperty("os.name", "?");
    }

    private static String arch() {
        return System.getProperty("os.arch", "?");
    }

    private static Path vatnHome() {
        String env = System.getenv("VATN_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".vatn");
    }

    private static boolean isRoot() { return "0".equals(System.getProperty("user.id", "")); }
    private static boolean isLinux() { return System.getProperty("os.name", "").toLowerCase().contains("linux"); }
    private static boolean isMac() { return System.getProperty("os.name", "").toLowerCase().contains("mac"); }

    private static String exists() { return "\u2714"; }
    private static String missing() { return "\u2718"; }

    private static String runQuiet(String... cmd) {
        try {
            var pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static int probeWebAdminPort() {
        if (isLinux()) {
            for (int p : new int[]{9108, 8080}) {
                String out = runQuiet("ss", "-tlnp", "sport = :" + p);
                if (out.contains("java")) return p;
            }
        } else if (isMac()) {
            for (int p : new int[]{9108, 8080}) {
                String out = runQuiet("lsof", "-ti", ":" + p);
                if (!out.isBlank()) return p;
            }
        }
        // Generic fallback: try to connect
        for (int p : new int[]{9108, 8080}) {
            try {
                var s = new java.net.Socket("127.0.0.1", p);
                s.close();
                return p;
            } catch (Exception e) { /* not listening */ }
        }
        return -1;
    }
}
