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
 *   VATN_JWT_SECRET   — JWT signing secret (min 32 chars); defaults to a dev-only placeholder
 *   VATN_ADMIN_USER   — admin username (default: admin)
 *   VATN_ADMIN_PASS   — admin password (default: vatnadmin — CHANGE IN PRODUCTION)
 *   PORT              — HTTP port (default: 8080, or pass as first CLI arg)
 * </pre>
 *
 * <p>Install as a background daemon with {@code install.sh}.
 */
public class VatnWebAdmin {

    public static void main(String[] args) {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        } else if (envPort != null && !envPort.isBlank()) {
            try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
        }

        // JWT secret — must be at least 32 chars in production
        String secret = System.getenv("VATN_JWT_SECRET");
        if (secret == null || secret.length() < 32) {
            secret = "default-development-jwt-signing-secret-key-32chars!!";
            System.out.println("[vatn-webadmin] WARNING: using default JWT secret. Set VATN_JWT_SECRET in production.");
        }

        String adminUser = System.getenv().getOrDefault("VATN_ADMIN_USER", "admin");
        String adminPass = System.getenv().getOrDefault("VATN_ADMIN_PASS", "vatnadmin");

        AuthConfig authConfig = AuthConfig.of(secret, (username, password) -> {
            if (adminUser.equals(username) && adminPass.equals(password)) {
                return Map.of("role", "admin");
            }
            throw new RuntimeException("Invalid credentials");
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
