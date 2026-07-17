package dev.vatn.plugins.devenv.scanner;

import dev.vatn.plugins.devenv.model.AppleInfo;
import dev.vatn.plugins.devenv.model.SimulatorEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * macOS / Xcode / hardware layer. No-op (returns {@code null}) on non-macOS (DCN-DEV-05).
 */
public final class AppleScanner {

    private final ScannerUtil util;

    public AppleScanner(ScannerUtil util) {
        this.util = util;
    }

    public AppleInfo scan() {
        if (!ScannerUtil.isMacOS()) return null;

        String chip = util.exec("sysctl", "-n", "machdep.cpu.brand_string").orElse("");
        long mem = util.exec("sysctl", "-n", "hw.memsize").map(AppleScanner::parseLong).orElse(-1L);
        boolean arm = util.exec("sysctl", "-n", "hw.optional.arm64").map(s -> s.trim().equals("1")).orElse(false);
        String xcode = util.exec("xcodebuild", "-version").map(ScannerUtil::extractVersion).orElse("");
        String clt = util.exec("pkgutil", "--pkg-info=com.apple.pkg.CLTools_Executables")
                .map(AppleScanner::parsePkgVersion).orElse("");
        List<SimulatorEntry> sims = util.exec("xcrun", "simctl", "list", "devices", "available")
                .map(AppleScanner::parseSimulators).orElse(List.of());

        return new AppleInfo(chip, mem, arm, xcode, clt, sims);
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1L; }
    }

    /** {@code pkgutil --pkg-info=…}: a "version: X" line. */
    static String parsePkgVersion(String output) {
        for (String line : output.lines().toList()) {
            String l = line.strip();
            if (l.startsWith("version:")) return l.substring("version:".length()).trim();
        }
        return "";
    }

    private static final Pattern SIM_LINE =
            Pattern.compile("^(.*?)\\s+\\(([0-9A-Fa-f-]{36})\\)\\s+\\((Booted|Shutdown)\\)");

    /** {@code xcrun simctl list devices available}: "    iPhone 15 (UDID) (Shutdown)" under "-- iOS 17 --". */
    static List<SimulatorEntry> parseSimulators(String output) {
        var out = new ArrayList<SimulatorEntry>();
        String runtime = "";
        for (String raw : output.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("--") && line.endsWith("--")) {
                runtime = line.replace("-", "").trim();
                continue;
            }
            Matcher m = SIM_LINE.matcher(line);
            if (m.find()) {
                out.add(new SimulatorEntry(m.group(1).trim(), runtime, m.group(3), m.group(2)));
            }
        }
        return List.copyOf(out);
    }
}
