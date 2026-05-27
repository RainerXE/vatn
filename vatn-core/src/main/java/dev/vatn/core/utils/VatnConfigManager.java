package dev.vatn.core.utils;

import dev.vatn.api.VJson;
import dev.vatn.core.VJsonServiceImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles persistent CLI and Runtime configuration (Registry peers, local settings).
 * Stores data in ~/.vatn/config.json
 */
public class VatnConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(VatnConfigManager.class);
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".vatn");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    
    private final VJson json = new VJsonServiceImpl();
    private ConfigData data = new ConfigData();

    public VatnConfigManager() {
        load();
    }

    public final void load() {
        if (!Files.exists(CONFIG_FILE)) {
            data = new ConfigData();
            return;
        }
        try {
            data = json.parse(Files.readString(CONFIG_FILE), ConfigData.class);
        } catch (IOException e) {
            logger.error("[VatnConfig] Failed to load config", e);
            data = new ConfigData();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_FILE, json.stringify(data));
        } catch (IOException e) {
            logger.error("[VatnConfig] Failed to save config", e);
        }
    }

    public void addRegistry(String id, String url) {
        data.registries.put(id, url);
        save();
    }

    public Map<String, String> getRegistries() {
        return data.registries;
    }

    public static class ConfigData {
        public Map<String, String> registries = new ConcurrentHashMap<>();
        public String defaultTrustLevel = "TRUSTED_ONLY";
    }
}
