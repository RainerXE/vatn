package dev.vatn.plugins.devenv.model;

/**
 * A locally-stored container image.
 *
 * @param name    repository name
 * @param tag     tag ("&lt;none&gt;" if untagged)
 * @param size    human-readable size string
 * @param created human-readable age (e.g. "3 weeks ago")
 */
public record ContainerImage(String name, String tag, String size, String created) {
}
