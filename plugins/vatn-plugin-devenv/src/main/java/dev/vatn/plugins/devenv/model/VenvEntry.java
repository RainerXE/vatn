package dev.vatn.plugins.devenv.model;

/**
 * A discovered Python virtual environment.
 *
 * @param path          environment root directory
 * @param pythonVersion interpreter version from pyvenv.cfg ("" if unknown)
 * @param type          venv / virtualenv / conda / uv
 * @param packageCount  count of entries in site-packages (-1 if not determined)
 */
public record VenvEntry(String path, String pythonVersion, VenvType type, int packageCount) {
}
