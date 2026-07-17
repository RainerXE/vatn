package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.AcceleratorEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects compute accelerators for ML/LLM workloads: NVIDIA (nvidia-smi), AMD ROCm (rocminfo),
 * and Apple Silicon GPU (Metal). Feeds the local-LLM scanner's GPU-offload capability (M5).
 */
public final class AcceleratorScanner {

    private final ScannerUtil util;

    public AcceleratorScanner(ScannerUtil util) {
        this.util = util;
    }

    public List<AcceleratorEntry> scan() {
        var out = new ArrayList<AcceleratorEntry>();

        // NVIDIA — CSV: "name, memory.total [MiB], driver_version"
        if (util.which("nvidia-smi").isPresent()) {
            util.exec("nvidia-smi",
                    "--query-gpu=name,memory.total,driver_version", "--format=csv,noheader,nounits")
                    .ifPresent(o -> out.addAll(parseNvidiaSmi(o)));
        }
        // AMD ROCm — presence-based for now.
        if (util.which("rocminfo").isPresent()) {
            out.add(new AcceleratorEntry("AMD", "ROCm device", -1L, "", "ROCM"));
        }
        // Apple Silicon — unified-memory GPU via Metal.
        if (ScannerUtil.isMacOS()) {
            boolean arm = util.exec("sysctl", "-n", "hw.optional.arm64").map(s -> s.trim().equals("1")).orElse(false);
            if (arm) {
                String chip = util.exec("sysctl", "-n", "machdep.cpu.brand_string").orElse("Apple Silicon");
                long mem = util.exec("sysctl", "-n", "hw.memsize").map(s -> {
                    try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1L; }
                }).orElse(-1L);
                out.add(new AcceleratorEntry("Apple", chip + " GPU", mem, "", "METAL"));
            }
        }
        return List.copyOf(out);
    }

    /** Parse {@code nvidia-smi --query-gpu=...} CSV (noheader,nounits): "name, MiB, driver". */
    static List<AcceleratorEntry> parseNvidiaSmi(String output) {
        var out = new ArrayList<AcceleratorEntry>();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            String[] f = line.split(",");
            if (f.length < 3) continue;
            String name = f[0].trim();
            long bytes = -1L;
            try { bytes = Long.parseLong(f[1].trim()) * 1024L * 1024L; } catch (Exception ignored) {}
            out.add(new AcceleratorEntry("NVIDIA", name, bytes, f[2].trim(), "CUDA"));
        }
        return List.copyOf(out);
    }
}
