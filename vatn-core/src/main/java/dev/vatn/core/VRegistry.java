package dev.vatn.core;

import dev.vatn.api.VPluginRegistry;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.security.VTrustLevel;
import dev.vatn.core.native_image.NativeImagePluginManager;
import dev.vatn.core.security.VPackageVerifier;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Generic Plugin Registry for the VATN Foundation.
 * Manages the lifecycle of VNodePlugins using PF4J.
 *
 * <p>In GraalVM native image mode, dynamic JAR loading is replaced by
 * {@link NativeImagePluginManager}. Plugins must either be compiled into the binary
 * (registered via {@link #registerPlugin}) or run as separate OUT_OF_PROCESS_BIN
 * / FFI_NATIVE processes communicating over OIPC/JSON.
 */
public class VRegistry implements VPluginRegistry {
    private static final Logger logger = LoggerFactory.getLogger(VRegistry.class);

    /**
     * True when running as a compiled GraalVM native binary.
     * Uses try/catch because graal-sdk is provided-scope (not on JVM runtime classpath).
     */
    static final boolean NATIVE_IMAGE;
    static {
        boolean native_ = false;
        try {
            native_ = org.graalvm.nativeimage.ImageInfo.inImageCode();
        } catch (NoClassDefFoundError ignored) {
            // JVM mode — graal-sdk not on runtime classpath
        }
        NATIVE_IMAGE = native_;
    }

    /** Signature bundle entries expected inside every signed VATN plugin JAR. */
    private static final String SIG_ENTRY = "META-INF/vatn.sig";
    private static final String KEY_ENTRY = "META-INF/vatn.key";

    private final PluginManager pluginManager;
    private final Path pluginPath;
    private final VPackageService packageService;
    private final VPackageVerifier packageVerifier = new VPackageVerifier();
    private final List<VNodePlugin> manualPlugins = new ArrayList<>();

    // Map to store assigned trust levels for each plugin ID
    private final Map<String, VTrustLevel> assignedTrust = new ConcurrentHashMap<>();

    public VRegistry(Path pluginPath, VPackageService packageService) {
        this.pluginPath = pluginPath;
        this.packageService = packageService;
        this.pluginManager = NATIVE_IMAGE
                ? new NativeImagePluginManager()
                : new DefaultPluginManager(pluginPath);
    }

    public void start() {
        logger.info("Loading VATN plugins from: {}", pluginPath);
        pluginManager.loadPlugins();
        
        // Security check placeholder (Link to VFirewall/VSecurityIdentity)
        validatePlugins();
        
        pluginManager.startPlugins();
    }

    /**
     * Loads a plugin from a .vnode package.
     */
    public String loadVNode(Path vnodeFile) throws Exception {
        var spec = packageService.loadPackage(vnodeFile);
        Path extractedDir = packageService.extractPackage(vnodeFile, spec.getDescriptor());
        
        String pluginId = pluginManager.loadPlugin(extractedDir);
        if (pluginId != null) {
            // Assign trust based on signature check in packageService
            assignedTrust.put(pluginId, spec.getSignatureFingerprint().isPresent() ? VTrustLevel.RESTRICTED : VTrustLevel.SANDBOXED);
            logger.info("Successfully loaded .vnode package: {} (Trust: {})", pluginId, assignedTrust.get(pluginId));
        }
        return pluginId;
    }

    private void validatePlugins() {
        for (PluginWrapper plugin : pluginManager.getPlugins()) {
            logger.info("Discovered plugin: {} ({})", plugin.getPluginId(), plugin.getDescriptor().getVersion());
            
            // SECURITY HOOK: Verify integrity and assign trust
            boolean isVerified = verifyIntegrity(plugin);
            if (isVerified) {
                // If it's a known identity, we assign RESTRICTED or FULL
                assignedTrust.put(plugin.getPluginId(), VTrustLevel.RESTRICTED);
                logger.info("Plugin {} verified. Assigned Trust: {}", plugin.getPluginId(), VTrustLevel.RESTRICTED);
            } else {
                assignedTrust.put(plugin.getPluginId(), VTrustLevel.SANDBOXED);
                logger.warn("UNTRUSTED PLUGIN: {} - Minimal trust level (SANDBOXED) assigned.", plugin.getPluginId());
            }
        }
    }

    /**
     * Explicitly sets the trust level for a plugin.
     */
    public void setTrustLevel(String pluginId, VTrustLevel level) {
        assignedTrust.put(pluginId, level);
    }

    /**
     * Returns the assigned trust level for a specific plugin.
     */
    public VTrustLevel getTrustLevel(String pluginId) {
        return assignedTrust.getOrDefault(pluginId, VTrustLevel.NONE);
    }

    /**
     * Verifies plugin JAR integrity using an Ed25519 signature bundle.
     *
     * <p>A correctly signed VATN plugin JAR must contain:
     * <ul>
     *   <li>{@code META-INF/vatn.sig} — Base64-encoded Ed25519 signature of the JAR bytes</li>
     *   <li>{@code META-INF/vatn.key} — Base64-encoded Ed25519 public key of the signer</li>
     * </ul>
     *
     * <p>Plugins without these entries are treated as UNSIGNED and assigned
     * {@link VTrustLevel#SANDBOXED}. Plugins with an invalid signature are rejected
     * ({@link VTrustLevel#NONE}) and cannot be started.
     *
     * @return {@code true} if the signature is valid, {@code false} if unsigned.
     * @throws SecurityException if the signature bundle is present but invalid.
     */
    private boolean verifyIntegrity(PluginWrapper plugin) {
        Path jarPath = plugin.getPluginPath();
        if (jarPath == null || !Files.exists(jarPath)) {
            logger.warn("[SECURITY] Cannot verify plugin {}: no JAR path available. Treating as SANDBOXED.",
                        plugin.getPluginId());
            return false;
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry sigEntry = jar.getJarEntry(SIG_ENTRY);
            JarEntry keyEntry = jar.getJarEntry(KEY_ENTRY);

            if (sigEntry == null || keyEntry == null) {
                // Unsigned plugin — allowed but sandboxed
                logger.warn("[SECURITY] Plugin {} has no VATN signature bundle ({}+{}). Assigning SANDBOXED trust.",
                            plugin.getPluginId(), SIG_ENTRY, KEY_ENTRY);
                return false;
            }

            String sigB64;
            String pubKeyB64;
            try (InputStream sigIn = jar.getInputStream(sigEntry);
                 InputStream keyIn = jar.getInputStream(keyEntry)) {
                sigB64    = new String(sigIn.readAllBytes(),  StandardCharsets.UTF_8).trim();
                pubKeyB64 = new String(keyIn.readAllBytes(), StandardCharsets.UTF_8).trim();
            }

            byte[] jarBytes = Files.readAllBytes(jarPath);
            boolean valid = packageVerifier.verify(jarBytes, sigB64, pubKeyB64);

            if (!valid) {
                logger.error("[SECURITY] Plugin {} has an INVALID signature — refusing to load.",
                             plugin.getPluginId());
                // Force PF4J to skip this plugin by setting NONE trust before startPlugins()
                assignedTrust.put(plugin.getPluginId(), VTrustLevel.NONE);
                throw new SecurityException("Plugin " + plugin.getPluginId() + " failed signature verification.");
            }

            logger.info("[SECURITY] Plugin {} signature verified OK.", plugin.getPluginId());
            return true;

        } catch (SecurityException e) {
            throw e; // Re-throw to stop plugin startup
        } catch (IOException e) {
            logger.error("[SECURITY] Could not read plugin JAR for {}: {}", plugin.getPluginId(), e.getMessage());
            return false;
        }
    }

    /**
     * Explicitly registers a plugin instance with the registry.
     * Useful for embedded testing or statically linked plugins.
     */
    public void registerPlugin(VNodePlugin plugin) {
        manualPlugins.add(plugin);
        assignedTrust.put(plugin.getId(), VTrustLevel.FULL); // Manually added are trusted by the host
    }

    /**
     * Returns all discovered and manually registered VNodePlugins.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<VNodePlugin> getPlugins() {
        List<VNodePlugin> plugins = new ArrayList<>(manualPlugins);
        
        // Also add PF4J extensions that implement VNodePlugin
        List<VNodePlugin> extensions = pluginManager.getExtensions(VNodePlugin.class);
        if (extensions != null) {
            plugins.addAll(extensions);
        }
        
        return plugins;
    }

    /**
     * Performs a hot-swap of a plugin by unloading it and reloading from its original location.
     */
    public boolean hotSwap(String pluginId) {
        logger.info("Hot-swapping plugin: {}", pluginId);
        try {
            pluginManager.unloadPlugin(pluginId);
            String loadedId = pluginManager.loadPlugin(pluginPath.resolve(pluginId + ".jar")); // Assuming standard JAR naming
            if (loadedId != null) {
                pluginManager.startPlugin(pluginId);
                logger.info("Hot-swap successful: {}", pluginId);
                return true;
            }
        } catch (Exception e) {
            logger.error("Hot-swap failed for plugin: {}", pluginId, e);
        }
        return false;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public void stop() {
        pluginManager.stopPlugins();
        pluginManager.unloadPlugins();
    }
}
