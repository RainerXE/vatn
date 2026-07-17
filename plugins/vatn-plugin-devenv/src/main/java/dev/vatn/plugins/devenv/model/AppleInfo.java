package dev.vatn.plugins.devenv.model;

import java.util.List;

/**
 * macOS / Xcode / hardware layer. {@code null} on non-macOS hosts.
 *
 * @param chip          CPU brand string (e.g. "Apple M3 Max")
 * @param memoryBytes   total physical/unified memory in bytes (-1 if unknown)
 * @param appleSilicon  true on arm64 Apple Silicon
 * @param xcodeVersion  Xcode version ("" if not installed)
 * @param cltVersion    Command Line Tools version ("" if not installed)
 * @param simulators    installed simulators
 */
public record AppleInfo(String chip, long memoryBytes, boolean appleSilicon,
                        String xcodeVersion, String cltVersion, List<SimulatorEntry> simulators) {
}
