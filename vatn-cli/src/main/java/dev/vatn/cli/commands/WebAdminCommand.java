package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(name = "webadmin",
         mixinStandardHelpOptions = true,
         description = "Manage the VATN Web Admin background service.",
         subcommands = {
             WebAdminCommand.Start.class,
             WebAdminCommand.Stop.class,
             WebAdminCommand.Restart.class,
             WebAdminCommand.Status.class,
             WebAdminCommand.Token.class,
             WebAdminCommand.Config.class
         })
public class WebAdminCommand {

    static final int DEV_PORT = 9108;
    static final int PROD_PORT = 8080;

    // ── helpers shared by all subcommands ────────────────────────────────────

    static Path vatnHome() {
        String env = System.getenv("VATN_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".vatn");
    }

    static boolean isRoot() { return "0".equals(System.getProperty("user.id", "")); }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    static Path nativeBinary() { return vatnHome().resolve("bin/vatn-webadmin"); }
    static Path jarFile()      { return vatnHome().resolve("lib/vatn-webadmin.jar"); }

    static boolean hasNativeBinary() { return Files.exists(nativeBinary()); }
    static boolean hasJar()          { return Files.exists(jarFile()); }

    static String serviceName() { return "vatn-webadmin"; }

    /** Returns the webadmin executable path, preferring native binary over JAR. */
    static Path executable() {
        if (hasNativeBinary()) return nativeBinary();
        return jarFile();
    }

    /** Run a command and return stdout, or blank on failure. */
    static String runQuiet(String... cmd) {
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

    static int runInteractive(String... cmd) throws Exception {
        var pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        return p.waitFor();
    }

    // ── subcommand: start ───────────────────────────────────────────────────

    @Command(name = "start", mixinStandardHelpOptions = true,
             description = "Start the VATN Web Admin service.")
    public static class Start implements Callable<Integer> {

        @Option(names = {"--dev"}, description = "Start in development mode (port " + DEV_PORT + ", no auth).")
        boolean dev;

        @Option(names = {"--port"}, description = "HTTP port (default: " + DEV_PORT + " for --dev, " + PROD_PORT + " otherwise).")
        String port;

        @Override
        public Integer call() throws Exception {
            int p = port != null ? Integer.parseInt(port) : (dev ? DEV_PORT : PROD_PORT);

            if (Files.exists(nativeBinary()) || Files.exists(jarFile())) {
                // Direct launch — build command args
                System.out.println("Starting VATN Web Admin on port " + p + (dev ? " (dev mode)" : "") + " …");
                String[] cmd;
                if (hasNativeBinary()) {
                    cmd = dev
                        ? new String[]{nativeBinary().toString(), "--dev", String.valueOf(p)}
                        : new String[]{nativeBinary().toString(), String.valueOf(p)};
                } else {
                    cmd = dev
                        ? new String[]{"java", "-jar", jarFile().toString(), "--dev", String.valueOf(p)}
                        : new String[]{"java", "-jar", jarFile().toString(), String.valueOf(p)};
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();
                pb.start();
                System.out.println("Web Admin started on port " + p + ".");
                return 0;
            }

            // Service-manager fallback
            if (isMac()) {
                return runInteractive("launchctl", "start", "dev.vatn.webadmin");
            } else if (isLinux()) {
                String scope = isRoot() ? "" : "--user";
                return runInteractive("systemctl", scope, "start", serviceName());
            }
            System.err.println("No webadmin installation found. Run the installer first.");
            return 1;
        }
    }

    // ── subcommand: stop ────────────────────────────────────────────────────

    @Command(name = "stop", description = "Stop the VATN Web Admin service.")
    public static class Stop implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            if (isMac()) {
                System.out.println("Stopping Web Admin …");
                return runInteractive("launchctl", "stop", "dev.vatn.webadmin");
            } else if (isLinux()) {
                String scope = isRoot() ? "" : "--user";
                System.out.println("Stopping Web Admin …");
                return runInteractive("systemctl", scope, "stop", serviceName());
            }
            System.err.println("Unsupported platform.");
            return 1;
        }
    }

    // ── subcommand: restart ─────────────────────────────────────────────────

    @Command(name = "restart", description = "Restart the VATN Web Admin service.")
    public static class Restart implements Callable<Integer> {

        @Option(names = {"--dev"}, description = "Restart in development mode.")
        boolean dev;

        @Option(names = {"--port"}, description = "HTTP port.")
        String port;

        @Override
        public Integer call() throws Exception {
            boolean restartDev = dev;
            String restartPort = port;
            new Stop().call();
            Start s = new Start();
            s.dev = restartDev;
            s.port = restartPort;
            return s.call();
        }
    }

    // ── subcommand: status ──────────────────────────────────────────────────

    @Command(name = "status", description = "Show VATN Web Admin service status.")
    public static class Status implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            String os = System.getProperty("os.name", "").toLowerCase();

            System.out.println("VATN Web Admin status");
            System.out.println("  Home:    " + vatnHome());
            System.out.println("  Binary:  " + nativeBinary() + "  " + (Files.exists(nativeBinary()) ? "✔" : "✗"));
            System.out.println("  JAR:     " + jarFile() + "  " + (Files.exists(jarFile()) ? "✔" : "✗"));

            if (os.contains("mac")) {
                Path plist = Path.of(System.getProperty("user.home"),
                    "Library/LaunchAgents/dev.vatn.webadmin.plist");
                System.out.println("  Plist:   " + plist + "  " + (Files.exists(plist) ? "✔" : "✗"));
                if (Files.exists(plist)) {
                    String out = runQuiet("launchctl", "list", "dev.vatn.webadmin");
                    System.out.println("  Status:  " + (out.isBlank() ? "not loaded" : "loaded"));
                }
            } else if (os.contains("linux")) {
                String scope = isRoot() ? "" : "--user";
                Path svc = Path.of(isRoot() ? "/etc/systemd/system" : System.getProperty("user.home") + "/.config/systemd/user",
                    "vatn-webadmin.service");
                System.out.println("  Unit:    " + svc + "  " + (Files.exists(svc) ? "✔" : "✗"));
                if (Files.exists(svc)) {
                    String active = runQuiet("systemctl", scope, "is-active", "vatn-webadmin");
                    String enabled = runQuiet("systemctl", scope, "is-enabled", "vatn-webadmin");
                    System.out.println("  Active:  " + (active.isBlank() ? "unknown" : active));
                    System.out.println("  Enabled: " + (enabled.isBlank() ? "unknown" : enabled));
                }
            }

            // Try to probe live port
            int port = probeRunningPort();
            if (port > 0) {
                System.out.println("  Port:    " + port + " (running)");
            } else {
                System.out.println("  Port:    (not detected)");
            }

            return 0;
        }

        private int probeRunningPort() {
            // Try common ports via /proc/net/tcp on Linux or lsof
            if (isLinux()) {
                for (int p : new int[]{9108, 8080}) {
                    String out = runQuiet("ss", "-tlnp", "sport = :" + p);
                    if (out.contains("vatn-webadmin") || out.contains("java")) return p;
                }
            } else if (isMac()) {
                for (int p : new int[]{9108, 8080}) {
                    String out = runQuiet("lsof", "-ti", ":" + p);
                    if (!out.isBlank()) return p;
                }
            }
            return -1;
        }
    }

    // ── subcommand: token ───────────────────────────────────────────────────

    @Command(name = "token",
             description = "Get or set the VATN Admin dashboard bearer token (VATN_ADMIN_TOKEN).")
    public static class Token implements Callable<Integer> {

        @Parameters(index = "0", description = "New token value (omit to show current token).", arity = "0..1")
        String newToken;

        @Override
        public Integer call() throws Exception {
            if (newToken != null) {
                return setToken(newToken);
            }
            return showToken();
        }

        private int showToken() {
            String current = System.getenv("VATN_ADMIN_TOKEN");
            if (current == null || current.isBlank()) {
                System.out.println("VATN_ADMIN_TOKEN is not set.");
                System.out.println("The admin dashboard will accept any token (auth disabled).");
                System.out.println("Set one with: vatn webadmin token <value>");
            } else {
                System.out.println("VATN_ADMIN_TOKEN: " + current);
            }
            return 0;
        }

        private int setToken(String token) {
            // Write to the service env override
            if (isLinux()) {
                String scope = isRoot() ? "" : "--user";
                Path unitDir = isRoot()
                    ? Path.of("/etc/systemd/system")
                    : Path.of(System.getProperty("user.home"), ".config/systemd/user");
                Path override = unitDir.resolve("vatn-webadmin.service.d");
                try {
                    Files.createDirectories(override);
                    Path envFile = override.resolve("env.conf");
                    String content = "[Service]\nEnvironment=VATN_ADMIN_TOKEN=" + token + "\n";
                    Files.writeString(envFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    runInteractive("systemctl", scope, "daemon-reload");
                    System.out.println("VATN_ADMIN_TOKEN written to " + envFile);
                    System.out.println("Restart: vatn webadmin restart");
                    return 0;
                } catch (Exception e) {
                    System.err.println("Failed to write token: " + e.getMessage());
                    return 1;
                }
            } else if (isMac()) {
                Path plist = Path.of(System.getProperty("user.home"),
                    "Library/LaunchAgents/dev.vatn.webadmin.plist");
                if (!Files.exists(plist)) {
                    System.err.println("LaunchAgent plist not found at " + plist);
                    System.err.println("Re-run the installer or add VATN_ADMIN_TOKEN to your shell profile.");
                    return 1;
                }
                // Inject env var into plist
                try {
                    String content = Files.readString(plist);
                    // Replace or add EnvironmentVariables dict
                    String envBlock = "<key>EnvironmentVariables</key>\n<dict>\n"
                        + "<key>VATN_ADMIN_TOKEN</key>\n<string>" + token + "</string>\n</dict>";
                    if (content.contains("<key>EnvironmentVariables</key>")) {
                        content = content.replaceAll(
                            "<key>EnvironmentVariables</key>\\s*<dict>.*?</dict>",
                            envBlock);
                    } else {
                        content = content.replace("</dict>\n</plist>",
                            envBlock + "\n</dict>\n</plist>");
                    }
                    Files.writeString(plist, content, StandardOpenOption.TRUNCATE_EXISTING);
                    runInteractive("launchctl", "unload", plist.toString());
                    runInteractive("launchctl", "load", plist.toString());
                    System.out.println("VATN_ADMIN_TOKEN set in LaunchAgent plist.");
                    return 0;
                } catch (Exception e) {
                    System.err.println("Failed to write token: " + e.getMessage());
                    return 1;
                }
            }
            System.err.println("Set VATN_ADMIN_TOKEN in your shell profile and restart.");
            return 1;
        }
    }

    // ── subcommand: config ──────────────────────────────────────────────────

    @Command(name = "config",
             description = "Show current VATN Web Admin configuration and environment.")
    public static class Config implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println("VATN Web Admin configuration");
            System.out.println("  VATN_HOME:        " + vatnHome());
            System.out.println("  Binary:           " + nativeBinary() + (hasNativeBinary() ? " (exists)" : ""));
            System.out.println("  JAR:              " + jarFile() + (hasJar() ? " (exists)" : ""));
            System.out.println("  Platform:         " + System.getProperty("os.name"));
            System.out.println("  User:             " + System.getProperty("user.name") + (isRoot() ? " (root)" : ""));
            System.out.println("  User.ID:          " + System.getProperty("user.id", "?"));
            System.out.println("  VATN_ADMIN_TOKEN: " + (System.getenv("VATN_ADMIN_TOKEN") != null ? "set" : "not set (auth disabled)"));
            System.out.println("  VATN_JWT_SECRET:  " + (System.getenv("VATN_JWT_SECRET") != null ? "set" : "not set (use --dev)"));
            System.out.println("  Default port:     " + (isRoot() ? PROD_PORT + " (production)" : DEV_PORT + " (dev)"));
            return 0;
        }
    }
}
