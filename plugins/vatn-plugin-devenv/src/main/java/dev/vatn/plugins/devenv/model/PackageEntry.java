package dev.vatn.plugins.devenv.model;

/**
 * A globally-installed package.
 *
 * @param name    package name
 * @param version version string ("" if unknown)
 * @param source  manager that owns it ("homebrew", "npm", "pip", "homebrew-cask")
 */
public record PackageEntry(String name, String version, String source) {
}
