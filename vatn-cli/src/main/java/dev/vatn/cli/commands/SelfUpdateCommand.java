package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(name = "selfupdate",
         mixinStandardHelpOptions = true,
         description = "Update VATN to the latest version from GitHub releases.")
public class SelfUpdateCommand implements Callable<Integer> {

    private static final String REPO = "RainerXE/vatn";
    private static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_BASE = "https://github.com/" + REPO + "/releases/download";

    @Option(names = {"--check-only"}, description = "Only check for updates, do not download.")
    private boolean checkOnly;

    @Option(names = {"--force"}, description = "Update even if already at the latest version.")
    private boolean force;

    @Override
    public Integer call() throws Exception {
        String currentVersion = currentVersion();
        System.out.println("Current version: " + currentVersion);

        ReleaseInfo latest = fetchLatestRelease();
        if (latest == null) {
            System.err.println("Error: Could not fetch latest release from GitHub.");
            return 1;
        }

        System.out.println("Latest release:  " + latest.tag);

        int cmp = compareVersions(toSemVer(latest.tag), toSemVer(currentVersion));
        if (cmp == 0 && !force) {
            System.out.println("Already up to date.");
            return 0;
        }
        if (cmp < 0 && !force) {
            System.out.println("Local version is newer than the latest release (dev build?). Use --force to override.");
            return 0;
        }

        if (checkOnly) {
            System.out.println("Update available: " + latest.tag);
            System.out.println("Run 'vatn selfupdate' to download and install.");
            return 0;
        }

        Path currentPath = currentBinaryPath();
        if (currentPath == null) {
            System.err.println("Error: Could not determine current binary path.");
            return 1;
        }

        String assetName = assetNameForCurrentPlatform();
        String url = DOWNLOAD_BASE + "/" + latest.tag + "/" + assetName;

        System.out.println("Downloading " + assetName + " …");
        Path tmp = Files.createTempFile("vatn-update-", ".tmp");
        try {
            download(url, tmp);
            if (!isJarMode()) {
                tmp.toFile().setExecutable(true);
            }
            Files.move(tmp, currentPath, StandardCopyOption.REPLACE_EXISTING);
            if (!isJarMode()) {
                currentPath.toFile().setExecutable(true);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            System.err.println("Download or replacement failed: " + e.getMessage());
            System.err.println("Try:  curl -fsSL -o " + currentPath + " " + url);
            return 1;
        }

        System.out.println("Updated to " + latest.tag + ".");
        return 0;
    }

    // ── version helpers ──────────────────────────────────────────────────────

    private static String currentVersion() {
        Properties props = new Properties();
        try (InputStream is = SelfUpdateCommand.class.getResourceAsStream("/vatn/version.properties")) {
            if (is != null) {
                props.load(is);
                return props.getProperty("version", "0.0.0");
            }
        } catch (IOException e) {
            // fall through
        }
        return "0.0.0";
    }

    /** Strip leading 'v' or 'V' prefix and return as semver-comparable string. */
    private static String toSemVer(String tag) {
        String s = tag.strip();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    /** Compare two dotted version strings. Returns -1, 0, or 1. */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("[-.]");
        String[] partsB = b.split("[-.]");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int na = i < partsA.length ? tryParseInt(partsA[i], 0) : 0;
            int nb = i < partsB.length ? tryParseInt(partsB[i], 0) : 0;
            if (na < nb) return -1;
            if (na > nb) return 1;
        }
        return 0;
    }

    private static int tryParseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    // ── GitHub API ───────────────────────────────────────────────────────────

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_API))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;

        String body = res.body();
        String tag = extractJsonString(body, "tag_name");
        if (tag == null || tag.isBlank()) return null;

        // Determine the platform asset name
        String expectedAsset = assetNameForCurrentPlatform();
        boolean hasAsset = body.contains("\"name\":\"" + expectedAsset + "\"");

        return new ReleaseInfo(tag, hasAsset);
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? null : json.substring(start, end);
    }

    // ── platform detection ───────────────────────────────────────────────────

    private static boolean isJarMode() {
        String command = ProcessHandle.current().info().command().orElse("");
        return command.endsWith(".jar") || command.contains("java");
    }

    private static Path currentBinaryPath() {
        if (isJarMode()) {
            try {
                var location = SelfUpdateCommand.class
                        .getProtectionDomain().getCodeSource().getLocation();
                if (location != null) {
                    Path p = Path.of(location.toURI());
                    if (Files.exists(p)) return p;
                }
            } catch (Exception e) {
                // fall through
            }
        }
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd != null && !cmd.isBlank()) {
            Path p = Path.of(cmd);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static String assetNameForCurrentPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String platform;
        if (os.contains("linux")) {
            platform = "Linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "Darwin";
        } else if (os.contains("win")) {
            platform = "Windows";
        } else {
            platform = os;
        }

        String archNorm;
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archNorm = "arm64";
        } else if (arch.contains("amd64") || arch.contains("x86_64")) {
            archNorm = "x86_64";
        } else {
            archNorm = arch;
        }

        return "vatn-" + platform + "-" + archNorm;
    }

    // ── download ─────────────────────────────────────────────────────────────

    private static void download(String url, Path target) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " for " + url);
        }
        try (InputStream in = res.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── inner types ──────────────────────────────────────────────────────────

    private record ReleaseInfo(String tag, boolean hasPlatformAsset) {}
}
