package dev.vatn.plugins.devenv.model;

/**
 * One detected language runtime or compiler.
 *
 * @param name       binary name probed (e.g. "java", "rustc")
 * @param path       resolved absolute path on PATH
 * @param rawVersion raw, untrimmed output of the version command
 * @param version    parsed semver-ish version extracted from {@code rawVersion}
 * @param source     inferred installation source
 */
public record RuntimeEntry(
        String name,
        String path,
        String rawVersion,
        String version,
        RuntimeSource source) {
}
