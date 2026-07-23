package dev.vatn.plugins.containers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JsonTemplateStore implements TemplateService {
    private static final Logger log = LoggerFactory.getLogger(JsonTemplateStore.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final Path filePath;
    private final Map<String, ContainerTemplate> templates = new ConcurrentHashMap<>();

    public JsonTemplateStore(Path dataDir) {
        this.filePath = dataDir.resolve("containers").resolve("templates.json");
        try {
            Files.createDirectories(this.filePath.getParent());
        } catch (IOException e) {
            log.warn("Cannot create templates directory: {}", e.getMessage());
        }
        load();
    }

    @Override
    public ContainerTemplate save(ContainerTemplate template) {
        var t = new ContainerTemplate(
            template.id() != null && !template.id().isBlank() ? template.id() : UUID.randomUUID().toString(),
            template.name(), template.description(), template.engine(),
            template.image(), template.containerName(), template.command(),
            template.entrypoint(), template.ports(), template.volumes(),
            template.env(), template.labels(), template.networkMode(),
            template.restartPolicy(), template.workDir(),
            template.resourceProfileId(),
            template.postStartCommands(), template.postStartWaitMs(),
            template.createdAt() > 0 ? template.createdAt() : System.currentTimeMillis()
        );
        templates.put(t.id(), t);
        save();
        return t;
    }

    @Override
    public Optional<ContainerTemplate> get(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    @Override
    public List<ContainerTemplate> list() {
        var list = new ArrayList<>(templates.values());
        list.sort(Comparator.comparing(ContainerTemplate::name));
        return Collections.unmodifiableList(list);
    }

    @Override
    public void delete(String id) {
        templates.remove(id);
        save();
    }

    private void load() {
        if (!Files.isReadable(filePath)) return;
        try {
            var bytes = Files.readAllBytes(filePath);
            var list = MAPPER.readValue(bytes, new TypeReference<List<ContainerTemplate>>() {});
            list.forEach(t -> templates.put(t.id(), t));
            log.debug("Loaded {} container templates", templates.size());
        } catch (IOException e) {
            log.warn("Failed to load templates: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            var list = new ArrayList<>(templates.values());
            MAPPER.writeValue(filePath.toFile(), list);
        } catch (IOException e) {
            log.error("Failed to save templates: {}", e.getMessage());
        }
    }
}
