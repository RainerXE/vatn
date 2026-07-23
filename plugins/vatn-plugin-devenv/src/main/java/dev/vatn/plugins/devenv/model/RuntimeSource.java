package dev.vatn.plugins.devenv.model;

/**
 * Where a resolved runtime/compiler binary comes from, inferred from its path.
 * Order of specificity matters when detecting (see RuntimeScanner).
 */
public enum RuntimeSource {
    PATH, MISE, SDKMAN, NVM, PYENV, RBENV, ASDF, HOMEBREW, SYSTEM, XCODE,
    CONDA, PYTHON_ORG, UNKNOWN
}
