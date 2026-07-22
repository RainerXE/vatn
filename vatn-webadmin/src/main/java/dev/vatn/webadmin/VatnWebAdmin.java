package dev.vatn.webadmin;

import dev.vatn.core.VNodeRunner;
import dev.vatn.plugins.admin.AdminPlugin;
import dev.vatn.plugins.auth.AuthConfig;
import dev.vatn.plugins.auth.AuthPlugin;
import dev.vatn.plugins.containers.ContainersPlugin;

import java.nio.file.Paths;
import java.util.Map;

/**
 * VATN Web Admin — the official bundled web administration node.
 *
 * <p>Boots a VATN node with:
 * <ul>
 *   <li>{@code AuthPlugin} — JWT-based login / bearer token auth</li>
 *   <li>{@code AdminPlugin} — System dashboard (heap, threads, DAG monitor, workloads)</li>
 *   <li>{@code ContainersPlugin} — Container GUI (Docker / Podman / Distrobox + xterm terminals)</li>
 * </ul>
 *
 * <p>Configuration via environment variables:
 * <pre>
 *   VATN_JWT_SECRET   — JWT signing secret (min 32 chars); exits with error if unset
 *   VATN_ADMIN_USER   — admin username; exits with error if unset
 *   VATN_ADMIN_PASS   — admin password; exits with error if unset
 *   PORT              — HTTP port (default: 8080, or pass as first CLI arg)
 * </pre>
 *
 * <p>Pass {@code --dev} as the first CLI argument to use development defaults
 * (printed warnings instead of fatal errors).
 *
 * <p>Install as a background daemon with {@code install.sh}.
 */
public class VatnWebAdmin {

    private static final String DEV_SECRET = "default-development-jwt-signing-secret-key-32chars!!";

    public static void main(String[] args) {
        boolean devMode = false;
        int port = 8080;
        for (String arg : args) {
            if ("--dev".equals(arg)) { devMode = true; continue; }
            try { port = Integer.parseInt(arg); } catch (NumberFormatException ignored) {}
        }
        if (!devMode) {
            String envPort = System.getenv("PORT");
            if (envPort != null && !envPort.isBlank()) {
                try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
            }
        }

        // JWT secret — must be at least 32 chars in production
        String secret = System.getenv("VATN_JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            if (!devMode) {
                System.err.println("[vatn-webadmin] FATAL: VATN_JWT_SECRET is not set. Use --dev for local development.");
                System.exit(1);
            }
            secret = DEV_SECRET;
            System.out.println("[vatn-webadmin] WARNING: using dev JWT secret (--dev mode).");
        } else if (secret.equals(DEV_SECRET) && !devMode) {
            System.err.println("[vatn-webadmin] FATAL: VATN_JWT_SECRET is the known dev default. Set a real secret or use --dev.");
            System.exit(1);
        }

        String envUser = System.getenv("VATN_ADMIN_USER");
        String envPass = System.getenv("VATN_ADMIN_PASS");
        if (envUser == null || envUser.isBlank() || envPass == null || envPass.isBlank()) {
            if (!devMode) {
                System.err.println("[vatn-webadmin] FATAL: VATN_ADMIN_USER and VATN_ADMIN_PASS must be set. Use --dev for local development.");
                System.exit(1);
            }
            envUser = "admin";
            envPass = "vatnadmin";
            System.out.println("[vatn-webadmin] WARNING: using dev admin credentials (--dev mode).");
        }
        final String adminUser = envUser;
        final String adminPass = envPass;

        AuthConfig authConfig = AuthConfig.of(secret, (username, password) -> {
            if (adminUser.equals(username) && adminPass.equals(password)) {
                return Map.of("role", "admin");
            }
            throw new dev.vatn.plugins.auth.InvalidCredentialsException("Invalid credentials");
        });

        VNodeRunner runner = VNodeRunner.create(port)
            .withDbPath(Paths.get(System.getProperty("user.home"), ".vatn", "webadmin.db"));

        runner.addPlugin(new AuthPlugin(authConfig));
        runner.addPlugin(new AdminPlugin());
        runner.addPlugin(new ContainersPlugin());

        runner.start();

        System.out.println("VATN Web Admin is running:");
        System.out.println("  → Admin Dashboard : http://localhost:" + runner.getBoundPort() + "/vatn/admin");
        System.out.println("  → Containers GUI  : http://localhost:" + runner.getBoundPort() + "/vatn/containers");
    }
}
