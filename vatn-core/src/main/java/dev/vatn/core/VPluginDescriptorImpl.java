package dev.vatn.core;

import dev.vatn.api.VPluginDescriptor;
import dev.vatn.spec.VPluginManifest;
import java.nio.file.Path;

/**
 * Internal implementation of VPluginDescriptor.
 * Wraps the universal VPluginManifest and stores the local filesystem source path.
 */
public record VPluginDescriptorImpl(
    String pluginId,
    VPluginManifest manifest,
    Path sourcePath
) implements VPluginDescriptor {

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public VPluginManifest getManifest() {
        return manifest;
    }

    @Override
    public Path getSourcePath() {
        return sourcePath;
    }
}
