package dev.vatn.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preventive guard against dynamic classloading in the VATN core.
 * Prepared for AOT/Native-Image strictness level D-57.
 */
public final class VatnClassGuard {
    private static final Logger logger = LoggerFactory.getLogger(VatnClassGuard.class);

    /**
     * Checks if a plugin is attempting to load a classes via dynamic lookup.
     * Currently a warning-level monitor, preparing for strict rejection in Phase 7.
     */
    public static void verifyClassAccess(String pluginId, String className) {
        if (className.startsWith("java.lang.reflect") || className.contains("ClassLoader")) {
            logger.warn("[SECURITY-AOT] Potential policy violation by {}: Attempted dynamic access to {}", pluginId, className);
            // In strict mode (Phase 7), we would throw a SecurityException here.
        }
    }

    private VatnClassGuard() {}
}
