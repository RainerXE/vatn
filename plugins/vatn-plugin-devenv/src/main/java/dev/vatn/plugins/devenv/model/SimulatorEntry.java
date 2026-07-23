package dev.vatn.plugins.devenv.model;

/**
 * An installed iOS/watchOS/tvOS simulator (macOS only).
 *
 * @param name    device name (e.g. "iPhone 15 Pro")
 * @param runtime OS runtime if known ("")
 * @param state   "Booted" | "Shutdown"
 * @param udid    device UDID
 */
public record SimulatorEntry(String name, String runtime, String state, String udid) {
}
