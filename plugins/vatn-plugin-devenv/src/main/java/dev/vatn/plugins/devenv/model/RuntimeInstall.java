package dev.vatn.plugins.devenv.model;

/**
 * One installed language runtime/toolchain (Python, Swift, …), enumerated across all sources
 * — the cross-source, multiplicity-aware analog of {@link JvmInstall} for non-JVM languages.
 *
 * @param language     "python", "swift", …
 * @param version      resolved version ("" if undetermined)
 * @param distribution CPython / Anaconda / Miniconda / PyPy / GraalPy / Apple / swift.org / …
 * @param source       PYENV, CONDA, PYTHON_ORG, HOMEBREW, SYSTEM, XCODE, …
 * @param path         install prefix or binary path
 * @param active       true if this is what the active {@code python3}/{@code swift} resolves to
 */
public record RuntimeInstall(String language, String version, String distribution,
                             RuntimeSource source, String path, boolean active) {
}
