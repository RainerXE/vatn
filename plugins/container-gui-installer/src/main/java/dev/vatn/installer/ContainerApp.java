package dev.vatn.installer;

import dev.vatn.core.VNodeRunner;
import dev.vatn.plugins.auth.AuthConfig;
import dev.vatn.plugins.auth.AuthPlugin;
import dev.vatn.plugins.containers.ContainersPlugin;

import java.util.Map;

public class ContainerApp {
    public static void main(String[] args) {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        // Set a default secure 32+ character JWT secret if not set via environment
        String secret = System.getenv("VATN_JWT_SECRET");
        if (secret == null || secret.length() < 32) {
            secret = "default-development-jwt-signing-secret-key-32chars!!";
        }

        AuthConfig authConfig = AuthConfig.of(secret, (username, password) -> {
            // Default user/pass validator for this bundled app
            if ("admin".equals(username) && "vatnadmin".equals(password)) {
                return Map.of("role", "admin");
            }
            throw new RuntimeException("Invalid credentials");
        });

        VNodeRunner runner = VNodeRunner.create(port)
            .withDbPath(java.nio.file.Paths.get(System.getProperty("user.home"), ".vatn", "containers.db"));

        runner.addPlugin(new AuthPlugin(authConfig));
        runner.addPlugin(new ContainersPlugin());

        runner.start();
        
        System.out.println("VATN Container GUI is running at http://localhost:" + runner.getBoundPort() + "/vatn/containers");
    }
}
