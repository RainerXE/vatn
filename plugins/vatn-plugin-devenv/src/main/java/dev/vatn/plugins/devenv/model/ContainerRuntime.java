package dev.vatn.plugins.devenv.model;

/**
 * A detected container runtime.
 *
 * @param engine   "docker" | "podman" | "orbstack" | "colima" | "lima" | "rancher"
 * @param version  engine/CLI version ("" if unknown)
 * @param socket   socket path or context ("" if unknown)
 * @param running  whether the engine responds (e.g. {@code docker info} succeeds)
 */
public record ContainerRuntime(String engine, String version, String socket, boolean running) {
}
