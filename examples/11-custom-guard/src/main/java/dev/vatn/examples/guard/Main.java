package dev.vatn.examples.guard;

import dev.vatn.api.VGuardService;
import dev.vatn.core.VNodeRunner;

/**
 * Example 11 — Custom VGuardService
 *
 * <p>Demonstrates replacing the default VATN guard with a custom implementation
 * that adds PII redaction, keyword filtering, and SSRF protection.
 *
 * <p>Run:
 * <pre>
 *   mvn package
 *   java -jar target/custom-guard-1.0-SNAPSHOT.jar
 * </pre>
 *
 * <p>The main thread exercises the guard directly so you can see its behaviour
 * without needing an HTTP client. The node also starts on port 8090 so the
 * standard /health and /info endpoints remain available.
 */
public class Main {

    public static void main(String[] args) {
        VNodeRunner runner = VNodeRunner.create(8090);
        runner.addPlugin(new GuardDemoPlugin());
        runner.start();

        VGuardService guard = runner.getContext()
                .getService(VGuardService.class)
                .orElseThrow(() -> new IllegalStateException("VGuardService not registered"));

        System.out.println("=== ContentFilterGuard Demo ===\n");

        // 1. PII redaction in sanitizeInput
        String raw = "Contact support@acme.com or call 555-867-5309. Card: 4111 1111 1111 1111";
        System.out.println("Input  : " + raw);
        System.out.println("Cleaned: " + guard.sanitizeInput("session-1", raw));

        System.out.println();

        // 2. Dangerous keyword in model output is blocked
        String safeOutput = "Here is the summary of the analysis.";
        String badOutput  = "Run: rm -rf /var/data to free space.";
        System.out.println("Output (safe) : " + guard.checkOutput(safeOutput));
        System.out.println("Output (bad)  : " + guard.checkOutput(badOutput));

        System.out.println();

        // 3. Tool call evaluation
        printDecision(guard, "agent-1", "read_file",   "{\"path\":\"/tmp/report.txt\"}");
        printDecision(guard, "agent-1", "shell_exec",  "{\"cmd\":\"ls -la\"}");
        printDecision(guard, "agent-1", "fetch_url",   "{\"url\":\"http://169.254.169.254/latest/meta-data/\"}");
        printDecision(guard, "agent-1", "fetch_url",   "{\"url\":\"https://api.example.com/v1/data\"}");

        System.out.println("\nNode running on port " + runner.getBoundPort() + " — Ctrl+C to exit.");
    }

    private static void printDecision(VGuardService guard,
                                      String agentId, String toolId, String args) {
        VGuardService.GuardDecision decision = guard.evaluateToolCall(agentId, toolId, args);
        System.out.printf("evaluateToolCall(%-12s, %-40s) -> %s%n", toolId, args, decision);
    }
}
