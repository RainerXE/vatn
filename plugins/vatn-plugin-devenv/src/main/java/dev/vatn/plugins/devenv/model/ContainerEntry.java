package dev.vatn.plugins.devenv.model;

/**
 * A container (running or stopped).
 *
 * @param id     short container id
 * @param name   container name
 * @param image  image reference
 * @param status status string (e.g. "Up 3 hours", "Exited (0) 2 days ago")
 * @param ports  port mappings ("" if none)
 */
public record ContainerEntry(String id, String name, String image, String status, String ports) {
}
