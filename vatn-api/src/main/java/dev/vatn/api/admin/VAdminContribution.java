package dev.vatn.api.admin;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

/**
 * An SPI for contributing navigation entries to the admin dashboard.
 * Plugins implement this interface to add custom pages to the admin UI,
 * providing a unique identifier, display title, route path, and optional icon.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VAdminContribution extends VService {

    /**
     * A unique identifier for this contribution entry.
     *
     * @return the contribution id
     */
    String id();

    /**
     * A human-readable title for the admin nav entry.
     *
     * @return the display title
     */
    String title();

    /**
     * The route path for this admin page.
     *
     * @return the path
     */
    String path();

    /**
     * An optional icon character for the nav entry.
     *
     * @return the icon character, defaults to a hollow square
     */
    default String icon() { return "◻"; }
}
