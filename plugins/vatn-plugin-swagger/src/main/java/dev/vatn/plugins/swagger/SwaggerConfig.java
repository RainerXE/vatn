package dev.vatn.plugins.swagger;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Swagger UI plugin.
 *
 * <p>Provide your OpenAPI 3.0 spec either as a raw JSON string or via
 * {@link OpenApiBuilder} for programmatic construction:
 *
 * <pre>{@code
 * // Raw JSON (load from file, resource, or string literal)
 * SwaggerConfig config = SwaggerConfig.of("My API", "1.0.0")
 *     .withSpec(Files.readString(Path.of("openapi.json")));
 *
 * // Programmatic builder
 * SwaggerConfig config = SwaggerConfig.of("Task API", "1.0.0")
 *     .withServer("http://localhost:8080")
 *     .withDescription("Manages TODO tasks")
 *     .withSpec(OpenApiBuilder.create()
 *         .path("/tasks", b -> b
 *             .get("List all tasks", 200, "array"))
 *         .path("/tasks/{id}", b -> b
 *             .get("Get task by id", 200, "object")
 *             .delete("Delete task", 204, null))
 *         .build());
 * }</pre>
 */
public final class SwaggerConfig {

    private final String title;
    private final String version;
    private final String description;
    private final List<String> servers;
    private final String docsPath;
    private final String specPath;
    private String spec;

    private SwaggerConfig(String title, String version, String description,
                          List<String> servers, String docsPath, String specPath, String spec) {
        this.title = title;
        this.version = version;
        this.description = description;
        this.servers = servers;
        this.docsPath = docsPath;
        this.specPath = specPath;
        this.spec = spec;
    }

    public static SwaggerConfig of(String title, String version) {
        return new SwaggerConfig(title, version, "", List.of(), "/docs", "/api-docs", null);
    }

    public SwaggerConfig withDescription(String description) {
        return new SwaggerConfig(title, version, description, servers, docsPath, specPath, spec);
    }

    public SwaggerConfig withServer(String url) {
        var list = new ArrayList<>(servers);
        list.add(url);
        return new SwaggerConfig(title, version, description, List.copyOf(list), docsPath, specPath, spec);
    }

    public SwaggerConfig withDocsPath(String path) {
        return new SwaggerConfig(title, version, description, servers, path, specPath, spec);
    }

    public SwaggerConfig withSpec(String specJson) {
        var copy = new SwaggerConfig(title, version, description, servers, docsPath, specPath, specJson);
        return copy;
    }

    public String getTitle()       { return title; }
    public String getVersion()     { return version; }
    public String getDescription() { return description; }
    public List<String> getServers() { return servers; }
    public String getDocsPath()    { return docsPath; }
    public String getSpecPath()    { return specPath; }
    public String getSpec()        { return spec; }
}
