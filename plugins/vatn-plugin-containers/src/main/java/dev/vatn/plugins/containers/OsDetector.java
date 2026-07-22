package dev.vatn.plugins.containers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class OsDetector {
    private static Path osReleasePath = Path.of("/etc/os-release");
    private static Map<String, String> cachedFields;

    private OsDetector() {}

    static void setOsReleasePath(Path path) {
        osReleasePath = path;
        cachedFields = null;
    }

    static Map<String, String> parseOsRelease(String content) {
        return content.lines()
            .filter(l -> l.contains("="))
            .map(l -> l.split("=", 2))
            .collect(Collectors.toUnmodifiableMap(
                kv -> kv[0].trim(),
                kv -> kv.length > 1 ? kv[1].trim().replaceAll("^\"|\"$", "") : ""
            ));
    }

    private static Map<String, String> readOsRelease() {
        if (cachedFields != null) return cachedFields;
        if (!Files.isReadable(osReleasePath)) return Map.of();
        try (Stream<String> lines = Files.lines(osReleasePath)) {
            cachedFields = parseOsRelease(String.join("\n", lines.toList()));
        } catch (IOException e) {
            cachedFields = Map.of();
        }
        return cachedFields;
    }

    public static boolean isFedora() {
        return "fedora".equalsIgnoreCase(readOsRelease().get("ID"));
    }

    public static boolean isRhel() {
        return "rhel".equalsIgnoreCase(readOsRelease().get("ID"));
    }

    public static boolean isFedoraDerivative() {
        var fields = readOsRelease();
        String id = fields.get("ID");
        String idLike = fields.get("ID_LIKE");
        if (id != null && id.equalsIgnoreCase("fedora")) return true;
        return idLike != null && idLike.contains("fedora");
    }

    public static boolean isUbuntu() {
        return "ubuntu".equalsIgnoreCase(readOsRelease().get("ID"));
    }

    public static boolean isDebianDerivative() {
        var fields = readOsRelease();
        String id = fields.get("ID");
        String idLike = fields.get("ID_LIKE");
        if (id != null && (id.equalsIgnoreCase("ubuntu") || id.equalsIgnoreCase("debian"))) return true;
        return idLike != null && (idLike.contains("ubuntu") || idLike.contains("debian"));
    }

    public static String distributionName() {
        var fields = readOsRelease();
        String name = fields.get("PRETTY_NAME");
        if (name != null && !name.isEmpty()) return name;
        name = fields.get("NAME");
        if (name != null && !name.isEmpty()) return name;
        String id = fields.get("ID");
        return id != null ? id : "unknown";
    }
}
