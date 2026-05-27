package dev.vatn.api;

import dev.vatn.spec.VPluginManifest;
import java.nio.file.Path;

/**
 * VPluginDescriptor bridges the gap between the raw VATN Universal schema and the
 * runtime engine. It describes exactly what a plugin is and where its binary or class
 * lies, but does not execute the plugin itself.
 */
@VatnApi(since = "1.0")
public interface VPluginDescriptor {

    /**
     * @return The unique identifier of the plugin.
     */
    String getPluginId();

    /**
     * @return The formal manifest parsed from vatn-plugin.json
     */
    VPluginManifest getManifest();

    /**
     * @return The physical path indicating where the plugin bundle or binary resides
     */
    Path getSourcePath();
}
