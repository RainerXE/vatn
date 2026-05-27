package dev.vatn.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vatn.api.VJson;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Scanner;
import java.util.function.Consumer;

/**
 * Jackson-based implementation of VJson for vatn-core.
 * Provides full support for parsing, serialization, merging, and path-querying.
 */
public class VJsonImpl implements VJson {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String stringify(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException("JSON stringify failed", e);
        }
    }

    @Override
    public <T> T parse(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed", e);
        }
    }

    @Override
    public String merge(String baseJson, String overrideJson) {
        try {
            JsonNode base = mapper.readTree(baseJson);
            JsonNode override = mapper.readTree(overrideJson);
            if (base.isObject() && override.isObject()) {
                ((ObjectNode) base).setAll((ObjectNode) override);
            }
            return mapper.writeValueAsString(base);
        } catch (Exception e) {
            throw new RuntimeException("JSON merge failed", e);
        }
    }

    @Override
    public <T> T path(String json, String path, Class<T> type) {
        try {
            JsonNode node = resolvePath(json, path);
            return mapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON path extraction failed", e);
        }
    }

    @Override
    public void stringifyStream(Collection<?> objects, OutputStream out) {
        try {
            for (Object obj : objects) {
                out.write(mapper.writeValueAsBytes(obj));
                out.write('\n');
            }
        } catch (Exception e) {
            throw new RuntimeException("JSON stream stringify failed", e);
        }
    }

    @Override
    public <T> void parseStream(InputStream in, Class<T> type, Consumer<T> target) {
        try (Scanner scanner = new Scanner(in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (!line.isBlank()) {
                    target.accept(mapper.readValue(line, type));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("JSON stream parse failed", e);
        }
    }

    @Override
    public String query(String json, String path) {
        try {
            JsonNode node = resolvePath(json, path);
            if (node.isMissingNode() || node.isNull()) return null;
            return node.isTextual() ? node.asText() : node.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public java.util.List<String> queryArray(String json, String path) {
        try {
            JsonNode node = resolvePath(json, path);
            if (!node.isArray()) return java.util.List.of();
            java.util.List<String> result = new java.util.ArrayList<>();
            node.forEach(el -> result.add(el.isTextual() ? el.asText() : el.toString()));
            return java.util.Collections.unmodifiableList(result);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private JsonNode resolvePath(String json, String path) throws Exception {
        JsonNode node = mapper.readTree(json);
        String cleanPath = path.startsWith("$.") ? path.substring(2) : path;
        for (String part : cleanPath.split("\\.")) {
            if (!part.isEmpty()) {
                node = node.path(part);
            }
        }
        return node;
    }
}
