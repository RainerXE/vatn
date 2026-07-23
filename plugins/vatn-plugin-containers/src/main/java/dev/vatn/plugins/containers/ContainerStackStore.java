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

public class ContainerStackStore implements ContainerStackService {
    private static final Logger log = LoggerFactory.getLogger(ContainerStackStore.class);
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final Path filePath;
    private final Map<String, ContainerStack> stacks = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public ContainerStackStore(Path workspacePath) {
        this.filePath = workspacePath.resolve("stacks.json");
        try {
            Files.createDirectories(this.filePath.getParent());
        } catch (IOException e) {
            log.warn("Cannot create stacks directory: {}", e.getMessage());
        }
        load();
    }

    @Override
    public List<ContainerStack> list() {
        rwLock.readLock().lock();
        try {
            var list = new ArrayList<>(stacks.values());
            list.sort(Comparator.comparingLong(ContainerStack::createdAt).reversed());
            return Collections.unmodifiableList(list);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Optional<ContainerStack> get(String id) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(stacks.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public ContainerStack save(ContainerStack stack) {
        var s = new ContainerStack(
            stack.id(),
            stack.name(),
            stack.description(),
            stack.services(),
            stack.createdAt() > 0 ? stack.createdAt() : System.currentTimeMillis()
        );
        rwLock.writeLock().lock();
        try {
            stacks.put(s.id(), s);
            save();
        } finally {
            rwLock.writeLock().unlock();
        }
        return s;
    }

    @Override
    public void delete(String id) {
        rwLock.writeLock().lock();
        try {
            stacks.remove(id);
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
            var map = MAPPER.readValue(bytes, new TypeReference<Map<String, ContainerStack>>() {});
            stacks.putAll(map);
            log.debug("Loaded {} container stacks", stacks.size());
        } catch (IOException e) {
            log.warn("Failed to load stacks: {}", e.getMessage());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void save() {
        try {
            MAPPER.writeValue(filePath.toFile(), stacks);
        } catch (IOException e) {
            log.error("Failed to save stacks: {}", e.getMessage());
        }
    }
}
