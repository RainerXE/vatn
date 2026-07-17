package dev.vatn.plugins.swagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Fluent builder for programmatic OpenAPI 3.0 specs. */
public final class OpenApiBuilder {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Consumer<PathBuilder>> paths = new LinkedHashMap<>();

    private OpenApiBuilder() {}

    public static OpenApiBuilder create() {
        return new OpenApiBuilder();
    }

    public OpenApiBuilder path(String path, Consumer<PathBuilder> spec) {
        paths.put(path, spec);
        return this;
    }

    public String build(SwaggerConfig config) {
        ObjectNode root = mapper.createObjectNode();
        root.put("openapi", "3.0.3");

        ObjectNode info = root.putObject("info");
        info.put("title", config.getTitle());
        info.put("version", config.getVersion());
        if (!config.getDescription().isBlank()) {
            info.put("description", config.getDescription());
        }

        if (!config.getServers().isEmpty()) {
            ArrayNode servers = root.putArray("servers");
            config.getServers().forEach(url -> servers.addObject().put("url", url));
        }

        ObjectNode pathsNode = root.putObject("paths");
        paths.forEach((path, specFn) -> {
            PathBuilder pb = new PathBuilder(mapper);
            specFn.accept(pb);
            pathsNode.set(path, pb.build());
        });

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OpenAPI spec", e);
        }
    }

    public static final class PathBuilder {
        private final ObjectMapper mapper;
        private final ObjectNode node;

        PathBuilder(ObjectMapper mapper) {
            this.mapper = mapper;
            this.node = mapper.createObjectNode();
        }

        public PathBuilder get(String summary, int status, String responseType) {
            return op("get", summary, status, responseType);
        }

        public PathBuilder post(String summary, int status, String responseType) {
            return op("post", summary, status, responseType);
        }

        public PathBuilder put(String summary, int status, String responseType) {
            return op("put", summary, status, responseType);
        }

        public PathBuilder patch(String summary, int status, String responseType) {
            return op("patch", summary, status, responseType);
        }

        public PathBuilder delete(String summary, int status, String responseType) {
            return op("delete", summary, status, responseType);
        }

        private PathBuilder op(String method, String summary, int status, String responseType) {
            ObjectNode op = node.putObject(method);
            op.put("summary", summary);
            ObjectNode responses = op.putObject("responses");
            ObjectNode resp = responses.putObject(String.valueOf(status));
            resp.put("description", status == 204 ? "No content" : "Success");
            if (responseType != null) {
                resp.putObject("content")
                    .putObject("application/json")
                    .putObject("schema")
                    .put("type", responseType);
            }
            return this;
        }

        ObjectNode build() { return node; }
    }
}
