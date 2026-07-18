package dev.vatn.plugins.swagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swagger / OpenAPI UI plugin for VATN.
 *
 * <p>Registers two routes:
 * <ul>
 *   <li>{@code GET /api-docs} — OpenAPI 3.0 JSON spec</li>
 *   <li>{@code GET /docs} — Swagger UI (loads spec from {@code /api-docs})</li>
 * </ul>
 *
 * <pre>{@code
 * SwaggerConfig config = SwaggerConfig.of("Task API", "1.0.0")
 *     .withServer("http://localhost:8080")
 *     .withSpec(OpenApiBuilder.create()
 *         .path("/tasks", b -> b.get("List tasks", 200, "array"))
 *         .build(config));
 *
 * VNodeRunner.create(8080)
 *     .addPlugin(new SwaggerPlugin(config))
 *     .addPlugin(new TaskPlugin())
 *     .start();
 * // → http://localhost:8080/docs
 * }</pre>
 */
public class SwaggerPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(SwaggerPlugin.class);

    private final SwaggerConfig config;

    public SwaggerPlugin(SwaggerConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.swagger"; }
    @Override public String getName()    { return "VATN Swagger Plugin"; }
    @Override public String getVersion() { return "1.0-alpha.14"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        String spec = resolveSpec();
        String specPath = config.getSpecPath();
        String docsPath = config.getDocsPath();

        // /api-docs — serve the OpenAPI JSON
        ctx.register(specPath, routes ->
            routes.get("", (req, res) ->
                res.header("Content-Type", "application/json; charset=utf-8").send(spec))
        );

        // /docs — serve Swagger UI HTML (CDN-backed)
        String html = buildSwaggerHtml(specPath, config.getTitle());
        ctx.register(docsPath, routes ->
            routes.get("", (req, res) -> res.sendHtml(html))
        );

        log.info("Swagger UI available at {} — spec at {}", docsPath, specPath);
    }

    @Override
    public void onShutdown() {}

    private String resolveSpec() {
        if (config.getSpec() != null && !config.getSpec().isBlank()) {
            return config.getSpec();
        }
        // Generate a minimal spec from config metadata
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("openapi", "3.0.3");
            root.putObject("info")
                .put("title", config.getTitle())
                .put("version", config.getVersion())
                .put("description", config.getDescription());
            if (!config.getServers().isEmpty()) {
                ArrayNode servers = root.putArray("servers");
                config.getServers().forEach(url -> servers.addObject().put("url", url));
            }
            root.putObject("paths");
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"" + config.getTitle() + "\",\"version\":\"" + config.getVersion() + "\"},\"paths\":{}}";
        }
    }

    private static String buildSwaggerHtml(String specUrl, String title) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <title>%s — API Docs</title>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
            </head>
            <body>
            <div id="swagger-ui"></div>
            <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
            <script>
              SwaggerUIBundle({
                url: "%s",
                dom_id: "#swagger-ui",
                presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
                layout: "BaseLayout",
                deepLinking: true
              });
            </script>
            </body>
            </html>
            """.formatted(title, specUrl);
    }
}
