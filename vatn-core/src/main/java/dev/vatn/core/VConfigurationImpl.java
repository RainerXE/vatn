package dev.vatn.core;

import dev.vatn.api.VConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of VConfiguration with hierarchical fallback:
 * 1. System Properties (-Dkey=value)
 * 2. Environment Variables (KEY_VALUE)
 * 3. .env File (Local Discovery)
 * 4. Defaults
 */
public class VConfigurationImpl implements VConfiguration {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(VConfigurationImpl.class);
    private final Map<String, String> fileValues = new HashMap<>();

    public VConfigurationImpl() {
        loadEnvFile(".env");
        loadJsonFile("frejConfig.json");
    }

    private void loadJsonFile(String fileName) {
        java.io.File file = new java.io.File(fileName);
        if (!file.exists()) {
            file = new java.io.File("..", fileName);
        }
        
        if (!file.exists()) {
            logger.debug("[VConfiguration] JSON config not found: {}", fileName);
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file);
            flattenJson(root, "");
            logger.info("[VConfiguration] SUCCESS: Loaded JSON config from: {}", file.getAbsolutePath());
        } catch (java.io.IOException e) {
            logger.error("[VConfiguration] Failed to load JSON file: {} - {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    private void flattenJson(com.fasterxml.jackson.databind.JsonNode node, String prefix) {
        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = fields.next();
                flattenJson(entry.getValue(), prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey());
            }
        } else {
            fileValues.put(prefix, node.asText());
        }
    }

    private void loadEnvFile(String fileName) {
        java.nio.file.Path path = java.nio.file.Paths.get(fileName);
        if (!java.nio.file.Files.exists(path)) {
            path = java.nio.file.Paths.get("..", fileName);
        }
        if (!java.nio.file.Files.exists(path)) return;
        try {
            Files.lines(path).forEach(line -> {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx > 0) {
                    String key = trimmed.substring(0, eqIdx).trim();
                    String value = trimmed.substring(eqIdx + 1).trim();
                    // Strip quotes if present
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    fileValues.put(key, value);
                }
            });
        } catch (IOException e) {
            logger.error("[VConfiguration] Failed to load .env file: {}", e.getMessage());
        }
    }

    @Override
    public Optional<String> get(String key) {
        // 1. System Properties
        String value = System.getProperty(key);
        if (value != null) return Optional.of(value);

        // 2. Environment Variables (standardize KEY_STYLE)
        String envKey = key.toUpperCase().replace('.', '_');
        value = System.getenv(envKey);
        if (value != null) return Optional.of(value);

        // 3. File Values (.env and frejConfig.json)
        value = fileValues.get(envKey);
        if (value != null) return Optional.of(value);
        value = fileValues.get(key);
        if (value != null) return Optional.of(value);

        return Optional.empty();
    }

    @Override
    public boolean isAot() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    @Override
    public String getDefaultTrustLevel() {
        return get("vatn.trust.default").orElse("SANDBOXED");
    }
}
