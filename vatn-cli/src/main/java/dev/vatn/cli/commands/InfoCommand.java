package dev.vatn.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;

@Command(name = "info", description = "Inquires technical information about a local or remote VATN node.")
public class InfoCommand implements Callable<Integer> {

    @Option(names = {"-p", "--port"}, description = "Local port to inquiry.", defaultValue = "8080")
    private int port;

    @Option(names = {"-u", "--url"}, description = "Remote URL to inquiry.")
    private String remoteUrl;

    @Override
    public Integer call() throws Exception {
        String target = remoteUrl != null ? remoteUrl : "http://localhost:" + port;
        if (!target.endsWith("/info")) {
            target = target.replaceAll("/$", "") + "/info";
        }

        System.out.println("🔍 Inquiring Node at " + target + "...");

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println("✅ Handshake Successful:\n");
                // Note: In a full impl we would use VJson to format this beautifully.
                // For now, we print the raw JSON or do a simple key-value split.
                String body = response.body().replace("{", "").replace("}", "").replace("\"", "");
                for (String kv : body.split(",")) {
                    System.out.println("  • " + kv.trim().replace(":", ": "));
                }
            } else {
                System.err.println("❌ Failed to inquiry node. Status: " + response.statusCode());
                return 1;
            }
        } catch (Exception e) {
            System.err.println("❌ Error connecting to node: " + e.getMessage());
            return 1;
        }

        return 0;
    }
}
