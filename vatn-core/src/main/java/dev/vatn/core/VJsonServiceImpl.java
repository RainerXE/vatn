package dev.vatn.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vatn.api.VJson;

/**
 * Jackson-based implementation of the VJson service.
 */
public class VJsonServiceImpl implements VJson {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String stringify(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to stringify object", e);
        }
    }

    @Override
    public <T> T parse(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    @Override
    public String merge(String baseJson, String overrideJson) {
        try {
            JsonNode base = mapper.readTree(baseJson);
            JsonNode override = mapper.readTree(overrideJson);
            if (base instanceof ObjectNode bNode && override instanceof ObjectNode oNode) {
                bNode.setAll(oNode);
                return mapper.writeValueAsString(bNode);
            }
            return overrideJson; // Fallback if not objects
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to merge JSON", e);
        }
    }

    @Override
    public <T> T path(String json, String path, Class<T> type) {
        try {
            JsonNode current = mapper.readTree(json);
            for (String segment : path.split("\\.")) {
                current = current.path(segment);
            }
            return mapper.treeToValue(current, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to resolve JSON path: " + path, e);
        }
    }

    @Override
    public void stringifyStream(java.util.Collection<?> objects, java.io.OutputStream out) {
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(out))) {
            for (Object obj : objects) {
                writer.write(mapper.writeValueAsString(obj));
                writer.newLine();
            }
            writer.flush();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to stringify NDJSON stream", e);
        }
    }

    @Override
    public <T> void parseStream(java.io.InputStream in, Class<T> type, java.util.function.Consumer<T> target) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    target.accept(mapper.readValue(line, type));
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to parse NDJSON stream", e);
        }
    }

    @Override
    public String query(String json, String path) {
        if (path.startsWith("$.")) path = path.substring(2);
        if (path.isEmpty() || path.equals("$")) return json;
        try {
            JsonNode current = mapper.readTree(json);
            for (String segment : path.split("\\.")) {
                current = current.path(segment);
            }
            if (current.isMissingNode() || current.isNull()) return null;
            return current.isTextual() ? current.asText() : current.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public java.util.List<String> queryArray(String json, String path) {
        if (path.startsWith("$.")) path = path.substring(2);
        try {
            JsonNode current = mapper.readTree(json);
            for (String segment : path.split("\\.")) {
                current = current.path(segment);
            }
            if (!current.isArray()) return java.util.List.of();
            java.util.List<String> result = new java.util.ArrayList<>();
            current.forEach(el -> result.add(el.isTextual() ? el.asText() : el.toString()));
            return java.util.Collections.unmodifiableList(result);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
