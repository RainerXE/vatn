package dev.vatn.plugins.containers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsonResourceProfileStore implements ResourceProfileService {
    private static final Logger log = LoggerFactory.getLogger(JsonResourceProfileStore.class);
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final Path filePath;
    private final Map<String, ResourceProfile> profiles = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public JsonResourceProfileStore(Path workspacePath) {
        this.filePath = workspacePath.resolve("profiles.json");
        try {
            Files.createDirectories(this.filePath.getParent());
        } catch (IOException e) {
            log.warn("Cannot create profiles directory: {}", e.getMessage());
        }
        load();
    }

    @Override
    public List<ResourceProfile> list() {
        rwLock.readLock().lock();
        try {
            var list = new ArrayList<>(profiles.values());
            list.sort(Comparator.comparingLong(ResourceProfile::createdAt).reversed());
            return Collections.unmodifiableList(list);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Optional<ResourceProfile> get(String id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(profiles.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public ResourceProfile save(ResourceProfile profile) {
        var p = new ResourceProfile(
            profile.id(),
            profile.name(),
            profile.description(),
            profile.cpuMin(),
            profile.cpuMax(),
            profile.memoryMin(),
            profile.memoryMax(),
            profile.deviceMounts(),
            profile.gpuMode(),
            profile.extraCliArgs(),
            profile.createdAt() > 0 ? profile.createdAt() : System.currentTimeMillis()
        );
        rwLock.writeLock().lock();
        try {
            profiles.put(p.id(), p);
            save();
        } finally {
            rwLock.writeLock().unlock();
        }
        return p;
    }

    @Override
    public void delete(String id) {
        rwLock.writeLock().lock();
        try {
            profiles.remove(id);
            save();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void load() {
        rwLock.writeLock().lock();
        try {
            if (!Files.isReadable(filePath)) return;
            var bytes = Files.readAllBytes(filePath);
            var map = MAPPER.readValue(bytes, new TypeReference<Map<String, ResourceProfile>>() {});
            profiles.putAll(map);
            log.debug("Loaded {} resource profiles", profiles.size());
        } catch (IOException e) {
            log.warn("Failed to load profiles: {}", e.getMessage());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void save() {
        try {
            MAPPER.writeValue(filePath.toFile(), profiles);
        } catch (IOException e) {
            log.error("Failed to save profiles: {}", e.getMessage());
        }
    }
}
