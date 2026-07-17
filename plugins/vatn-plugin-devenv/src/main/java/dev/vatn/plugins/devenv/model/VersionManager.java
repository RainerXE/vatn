package dev.vatn.plugins.devenv.model;

import java.util.List;

/**
 * A detected language/tool version manager.
 *
 * @param name         e.g. "mise", "sdkman", "nvm", "pyenv", "rbenv", "asdf"
 * @param installRoot  on-disk root directory
 * @param managedTools tool (or version) names discovered under the root
 */
public record VersionManager(String name, String installRoot, List<String> managedTools) {
}
