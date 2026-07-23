package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.ContainerEntry;
import dev.vatn.plugins.devenv.model.ContainerImage;
import dev.vatn.plugins.devenv.model.ContainerInventory;
import dev.vatn.plugins.devenv.model.ContainerRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects container runtimes and, for the first responding engine, lists containers and images.
 * Uses Go-template {@code --format} output with a {@code |} delimiter for robust parsing.
 */
public final class ContainerScanner {

    private static final String PS_FMT = "{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}";
    private static final String IMG_FMT = "{{.Repository}}|{{.Tag}}|{{.Size}}|{{.CreatedSince}}";

    private final ScannerUtil util;

    public ContainerScanner(ScannerUtil util) {
        this.util = util;
    }

    public ContainerInventory scan() {
        var runtimes = new ArrayList<ContainerRuntime>();
        for (String engine : List.of("docker", "podman")) {
            if (util.which(engine).isEmpty()) continue;
            String version = util.exec(engine, "--version").map(ScannerUtil::extractVersion).orElse("");
            boolean up = util.exec(engine, "info", "--format", "{{.ServerVersion}}").isPresent();
            runtimes.add(new ContainerRuntime(engine, version, "", up));
        }
        // Auxiliary runtimes detected by presence only.
        for (String aux : List.of("orbstack", "colima", "lima", "rancher")) {
            if (util.which(aux).isPresent()) {
                runtimes.add(new ContainerRuntime(aux,
                        util.exec(aux, "--version").map(ScannerUtil::extractVersion).orElse(""), "", false));
            }
        }

        // Use the first responding docker-compatible engine for containers/images.
        String active = runtimes.stream().filter(ContainerRuntime::running)
                .map(ContainerRuntime::engine).findFirst().orElse(null);
        List<ContainerEntry> containers = List.of();
        List<ContainerImage> images = List.of();
        if (active != null) {
            containers = util.exec(active, "ps", "-a", "--format", PS_FMT)
                    .map(ContainerScanner::parseContainers).orElse(List.of());
            images = util.exec(active, "images", "--format", IMG_FMT)
                    .map(ContainerScanner::parseImages).orElse(List.of());
        }
        return new ContainerInventory(List.copyOf(runtimes), containers, images);
    }

    static List<ContainerEntry> parseContainers(String output) {
        var out = new ArrayList<ContainerEntry>();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            String[] f = line.split("\\|", -1);
            out.add(new ContainerEntry(
                    get(f, 0), get(f, 1), get(f, 2), get(f, 3), get(f, 4)));
        }
        return List.copyOf(out);
    }

    static List<ContainerImage> parseImages(String output) {
        var out = new ArrayList<ContainerImage>();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            String[] f = line.split("\\|", -1);
            out.add(new ContainerImage(get(f, 0), get(f, 1), get(f, 2), get(f, 3)));
        }
        return List.copyOf(out);
    }

    private static String get(String[] a, int i) {
        return i < a.length ? a[i].trim() : "";
    }
}
