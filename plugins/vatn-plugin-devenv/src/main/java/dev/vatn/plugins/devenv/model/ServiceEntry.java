package dev.vatn.plugins.devenv.model;

/**
 * A background service managed by a package manager (e.g. {@code brew services}).
 *
 * @param name   service name
 * @param status "started" | "stopped" | "error" | "none" | "scheduled" | …
 * @param user   owning user ("" if unknown)
 */
public record ServiceEntry(String name, String status, String user) {
}
