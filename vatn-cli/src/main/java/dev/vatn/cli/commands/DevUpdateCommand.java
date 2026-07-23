package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "dev-update",
         aliases = {"pull-build", "update"},
         mixinStandardHelpOptions = true,
         description = "Pull latest source from GitHub, rebuild with Maven, and update local VATN installation.")
public class DevUpdateCommand implements Callable<Integer> {

    private static final String SCRIPT_NAME = "vatn-dev-update.sh";
    private static final String RAW_URL = "https://raw.githubusercontent.com/RainerXE/vatn/main/" + SCRIPT_NAME;

    @Option(names = {"--repo-dir"},
            description = "Path to existing VATN git repository (default: auto-detect).")
    private String repoDir;

    @Option(names = {"--branch"},
            description = "Git branch (default: main).")
    private String branch = "main";

    @Override
    public Integer call() throws Exception {
        // Try to run the shell script if found locally
        Path script = findScript();
        if (script != null) {
            return runScript(script);
        }

        // Inline fallback
        return doDevUpdate();
    }

    // ── script discovery ────────────────────────────────────────────────────

    private Path findScript() {
        List<Path> candidates = new ArrayList<>();

        // Current directory
        candidates.add(Path.of("").toAbsolutePath().normalize().resolve(SCRIPT_NAME));

        // VATN_SRC_DIR
        String env = System.getenv("VATN_SRC_DIR");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env).resolve(SCRIPT_NAME));
        }

        // VATN_HOME/bin
        candidates.add(vatnHome().resolve("bin/" + SCRIPT_NAME));

        // Repo dir option
        if (repoDir != null && !repoDir.isBlank()) {
            candidates.add(Path.of(repoDir).resolve(SCRIPT_NAME));
        }

        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p;
        }
        return null;
    }

    private int runScript(Path script) throws Exception {
        System.out.println("Found dev-update script at " + script);
        ProcessBuilder pb = new ProcessBuilder(script.toString());
        if (repoDir != null && !repoDir.isBlank()) {
            pb.environment().put("VATN_SRC_DIR", Path.of(repoDir).toAbsolutePath().normalize().toString());
        }
        pb.environment().put("BRANCH", branch);
        pb.inheritIO();
        return pb.start().waitFor();
    }

    // ── inline fallback ─────────────────────────────────────────────────────

    private int doDevUpdate() throws Exception {
        System.out.println("Looking for vatn-dev-update.sh script…");
        System.out.println("Downloading dev-update script from GitHub …");

        Path tmp = Files.createTempFile("vatn-dev-update-", ".sh");
        try {
            download(RAW_URL, tmp);
            tmp.toFile().setExecutable(true);
            ProcessBuilder pb = new ProcessBuilder(tmp.toString());
            pb.environment().put("BRANCH", branch);
            if (repoDir != null && !repoDir.isBlank()) {
                pb.environment().put("VATN_SRC_DIR", Path.of(repoDir).toAbsolutePath().normalize().toString());
            }
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) {
                System.err.println("Dev-update script failed.");
                return exit;
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Could not download dev-update script: " + e.getMessage());
            System.err.println("Clone the repo and run: bash vatn-dev-update.sh");
            return 1;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── download ────────────────────────────────────────────────────────────

    private static void download(String url, Path target) throws Exception {
        var http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        var req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(java.time.Duration.ofSeconds(30))
            .GET()
            .build();
        try (var in = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream()).body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── paths ───────────────────────────────────────────────────────────────

    private static Path vatnHome() {
        String env = System.getenv("VATN_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".vatn");
    }
}
