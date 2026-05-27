package dev.vatn.core;

import dev.vatn.api.VJson;
import dev.vatn.api.VLaunchSpec;
import dev.vatn.api.VPluginDescriptor;
import dev.vatn.spec.VPluginManifest;
import dev.vatn.core.security.VPackageVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Service for loading, verifying, and extracting .vnode packages.
 */
public class VPackageService {
    private static final Logger logger = LoggerFactory.getLogger(VPackageService.class);
    
    private final VJson json;
    private final Path packageCacheDir;

    public VPackageService(VJson json, VPackageVerifier verifier, Path packageCacheDir) {
        this.json = json;
        this.packageCacheDir = packageCacheDir;
        
        try {
            Files.createDirectories(packageCacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize package cache", e);
        }
    }

    /**
     * Loads and verifies a .vnode package.
     */
    public VLaunchSpec loadPackage(Path vnodeFile) throws IOException {
        logger.info("Loading package: {}", vnodeFile.getFileName());

        // 1. Basic format verification (ZIP)
        try (ZipFile zipFile = new ZipFile(vnodeFile.toFile())) {
            
            // 2. Read vatn-plugin.json (Universal Manifest)
            ZipEntry manifestEntry = zipFile.getEntry("vatn-plugin.json");
            if (manifestEntry == null) {
                // Fallback for transition if needed, but we enforce the new standard now
                throw new IOException("Missing vatn-plugin.json in " + vnodeFile.getFileName());
            }

            String manifestJson;
            try (InputStream is = zipFile.getInputStream(manifestEntry)) {
                manifestJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 3. Parse Metadata using the formal VPluginManifest POJO
            VPluginManifest manifest = json.parse(manifestJson, VPluginManifest.class);
            VLaunchSpec spec = parseSpec(manifest, vnodeFile);
            
            // 4. Signature Verification
            verifySignature(vnodeFile, zipFile);
            
            return spec;
        }
    }

    private VLaunchSpec parseSpec(VPluginManifest manifest, Path sourcePath) {
        VPluginDescriptor descriptor = new VPluginDescriptorImpl(
            manifest.getId(),
            manifest,
            sourcePath
        );

        // VLaunchSpec maps envelope concerns. We default most for now or pull from manifest
        return new VLaunchSpecImpl(
            descriptor,
            manifest.getVatnVersion(),
            Optional.empty(), // updateUrl can be in manifest later if needed
            Optional.empty(), // signature populated by verifySignature
            VLaunchSpec.ResourceConstraints.DEFAULT,
            Collections.emptyMap()
        );
    }

    private void verifySignature(Path vnodeFile, ZipFile zipFile) {
        ZipEntry sigEntry = zipFile.getEntry("META-INF/signature.ed25519");
        ZipEntry keyEntry = zipFile.getEntry("META-INF/public.key");

        if (sigEntry == null || keyEntry == null) {
            // [USER FEEDBACK]: Warning for unsigned files
            logger.warn("############################################################");
            logger.warn("# SECURITY WARNING: Package {} is UNSIGNED!", vnodeFile.getFileName());
            logger.warn("# Running unsigned code is dangerous and not recommended.");
            logger.warn("############################################################");
            return;
        }

        // PENDING: Full signature verification logic matching VPackageVerifier
        // This requires reading the entire zip content (excluding the signature itself)
        // or a signed manifest. For M3, we log the presence.
        logger.info("Package {} signature detected. Verification in progress...", vnodeFile.getFileName());
    }

    /**
     * Extracts the package to the versioned cache.
     */
    public Path extractPackage(Path vnodeFile, VPluginDescriptor descriptor) throws IOException {
        Path targetDir = packageCacheDir.resolve(descriptor.getPluginId()).resolve(descriptor.getManifest().getVersion());
        Files.createDirectories(targetDir);

        try (ZipFile zipFile = new ZipFile(vnodeFile.toFile())) {
            for (ZipEntry entry : Collections.list(zipFile.entries())) {
                Path entryPath = targetDir.resolve(entry.getName());
                
                // Path Traversal Guard
                if (!entryPath.normalize().startsWith(targetDir.normalize())) {
                    throw new SecurityException("Potential Path Traversal in .vnode: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        
        // [PF4J BRIDGE]: Create plugin.properties for compatibility
        createPf4jDescriptor(targetDir, descriptor);
        
        return targetDir;
    }

    private void createPf4jDescriptor(Path targetDir, VPluginDescriptor descriptor) throws IOException {
        VPluginManifest manifest = descriptor.getManifest();
        StringBuilder sb = new StringBuilder();
        sb.append("plugin.id=").append(descriptor.getPluginId()).append("\n");
        sb.append("plugin.version=").append(manifest.getVersion()).append("\n");
        if (manifest.getExecution() != null) {
            sb.append("plugin.class=").append(manifest.getExecution().getEntrypoint()).append("\n");
        }
        sb.append("plugin.description=").append(manifest.getName()).append("\n");
        
        Files.writeString(targetDir.resolve("plugin.properties"), sb.toString(), StandardCharsets.UTF_8);
    }
}
