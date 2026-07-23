package dev.vatn.plugins.devenv.model;

/**
 * One installed Java runtime (JDK/JRE), discovered by enumerating JDK homes and reading each
 * home's {@code release} file — so multiple installs across system locations and version
 * managers are all captured, with distribution and the active one marked.
 *
 * @param version      JAVA_VERSION (e.g. "21.0.8")
 * @param distribution friendly distribution (GraalVM, Oracle, Temurin, Corretto, Zulu, …)
 * @param vendor       raw IMPLEMENTOR string from the release file
 * @param arch         OS_ARCH (e.g. "aarch64")
 * @param path         JDK home directory
 * @param source       where it lives (SYSTEM, SDKMAN, HOMEBREW, …)
 * @param active       true if this is the JDK the active {@code java}/JAVA_HOME resolves to
 */
public record JvmInstall(String version, String distribution, String vendor, String arch,
                         String path, RuntimeSource source, boolean active) {
}
