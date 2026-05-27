package dev.vatn.core;

import dev.vatn.api.VLaunchSpec;
import dev.vatn.api.VPluginDescriptor;
import java.util.Map;
import java.util.Optional;

/**
 * Internal implementation of the launch specification.
 */
public record VLaunchSpecImpl(
    VPluginDescriptor descriptor,
    String requiresVatn,
    Optional<String> updateUrl,
    Optional<String> signatureFingerprint,
    ResourceConstraints resources,
    Map<String, String> permissions
) implements VLaunchSpec {

    @Override
    public VPluginDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public String getRequiresVatn() {
        return requiresVatn;
    }

    @Override
    public Optional<String> getUpdateUrl() {
        return updateUrl;
    }

    @Override
    public Optional<String> getSignatureFingerprint() {
        return signatureFingerprint;
    }

    @Override
    public ResourceConstraints getResources() {
        return resources;
    }

    @Override
    public Map<String, String> getPermissions() {
        return permissions;
    }
}
