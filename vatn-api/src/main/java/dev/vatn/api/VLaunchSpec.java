package dev.vatn.api;

import java.util.Map;
import java.util.Optional;

/**
 * Descriptor for how a plugin should be launched and managed by the VATN node.
 * This is the distribution-level contract (from vnode.json).
 */
@VatnApi(since = "1.0")
public interface VLaunchSpec {

    /**
     * The plugin descriptor contained within this package.
     */
    VPluginDescriptor getDescriptor();

    /**
     * Mandatory range check for VATN host version compatibility (e.g. ">=1.0.0").
     */
    String getRequiresVatn();

    /**
     * URL where update checks and new packages can be fetched from.
     */
    Optional<String> getUpdateUrl();

    /**
     * Cryptographic signature fingerprint (Ed25519) for package integrity verification.
     */
    Optional<String> getSignatureFingerprint();

    /**
     * Declared resource constraints for the plugin runtime.
     */
    ResourceConstraints getResources();

    /**
     * Permissions required by the plugin (files, networking, process).
     */
    Map<String, String> getPermissions();

    /**
     * Resource constraint definitions.
     */
    record ResourceConstraints(
        long memoryLimitMb,
        double cpuShares,
        long storageLimitMb
    ) {
        public static final ResourceConstraints DEFAULT = new ResourceConstraints(256, 1.0, 50);
    }
}
