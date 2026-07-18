package dev.vatn.junit;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import dev.vatn.api.workflow.VDagEngine;
import dev.vatn.api.workflow.VDagRunState;
import org.junit.jupiter.api.Assertions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Domain-specific assertion helpers for VATN tests.
 *
 * <p>All methods throw {@link AssertionError} on failure, consistent with JUnit 5.
 *
 * <pre>{@code
 * VatnAssertions.assertHttpStatus(port, "GET",  "/auth/me", 401);
 * VatnAssertions.assertHttpStatus(port, "POST", "/auth/login",
 *     "{\"username\":\"alice\",\"password\":\"secret\"}", 200);
 * VatnAssertions.assertDagCompletes(engine, "my-dag", Duration.ofSeconds(5));
 * VatnAssertions.assertPluginLoaded(ctx, "dev.vatn.plugins.auth");
 * VatnAssertions.assertNoThreadLeak(baselineCount, node::stop);
 * }</pre>
 */
public final class VatnAssertions {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private VatnAssertions() {}

    // ── HTTP assertions ───────────────────────────────────────────────────────

    /** Assert that a GET to {@code path} on the test node returns {@code expectedStatus}. */
    public static HttpResponse<String> assertHttpStatus(int port, String method, String path,
                                                        int expectedStatus) {
        return assertHttpStatus(port, method, path, null, null, expectedStatus);
    }

    /** Assert HTTP status with a JSON body. */
    public static HttpResponse<String> assertHttpStatus(int port, String method, String path,
                                                        String body, int expectedStatus) {
        return assertHttpStatus(port, method, path, body, "application/json", expectedStatus);
    }

    /** Full overload: method, path, body, content-type, expected status. */
    public static HttpResponse<String> assertHttpStatus(int port, String method, String path,
                                                        String body, String contentType,
                                                        int expectedStatus) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10));

            if (body != null) {
                b.header("Content-Type", contentType != null ? contentType : "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(expectedStatus, resp.statusCode(),
                    "HTTP " + method + " " + path + " → expected " + expectedStatus
                    + " but got " + resp.statusCode() + ". Body: " + truncate(resp.body(), 200));
            return resp;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("HTTP request failed: " + method + " " + path, e);
        }
    }

    /** Assert status with a Bearer token header. */
    public static HttpResponse<String> assertHttpStatusWithBearer(int port, String method,
                                                                   String path, String token,
                                                                   int expectedStatus) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .method(method, HttpRequest.BodyPublishers.noBody());

            HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Assertions.assertEquals(expectedStatus, resp.statusCode(),
                    "HTTP " + method + " " + path + " (Bearer) → expected " + expectedStatus
                    + " but got " + resp.statusCode() + ". Body: " + truncate(resp.body(), 200));
            return resp;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("HTTP request failed: " + method + " " + path, e);
        }
    }

    /** Assert the response body contains the given substring. */
    public static void assertBodyContains(HttpResponse<String> response, String substring) {
        String body = response.body();
        if (!body.contains(substring)) {
            Assertions.fail("Expected response body to contain: [" + substring
                    + "] but got: " + truncate(body, 400));
        }
    }

    /** Assert the response body does NOT contain the given substring (e.g., secrets). */
    public static void assertBodyNotContains(HttpResponse<String> response, String substring) {
        String body = response.body();
        if (body.contains(substring)) {
            Assertions.fail("Expected response body NOT to contain: [" + substring + "]");
        }
    }

    // ── DAG assertions ────────────────────────────────────────────────────────

    /** Trigger a DAG and assert it completes successfully within the given timeout. */
    public static void assertDagCompletes(VDagEngine engine, String dagId, Duration timeout) {
        var run = engine.trigger(dagId);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var state = engine.getRunById(run.runId())
                    .map(r -> r.state())
                    .orElse(null);
            if (state == VDagRunState.SUCCESS) return;
            if (state == VDagRunState.FAILED)
                Assertions.fail("DAG '" + dagId + "' run " + run.runId() + " FAILED");
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        Assertions.fail("DAG '" + dagId + "' did not complete within " + timeout);
    }

    // ── Plugin assertions ─────────────────────────────────────────────────────

    /** Assert a plugin with the given ID is registered and active in the context. */
    public static void assertPluginLoaded(VNodeContext ctx, String pluginId) {
        var plugins = ctx.getPluginRegistry().getPlugins();
        boolean found = plugins.stream().anyMatch(p -> pluginId.equals(p.getId()));
        if (!found) {
            Assertions.fail("Expected plugin [" + pluginId + "] to be loaded. "
                    + "Loaded plugins: " + plugins.stream().map(VNodePlugin::getId).toList());
        }
    }

    // ── Thread / resource leak assertions ────────────────────────────────────

    /**
     * Assert that running {@code action} does not create a net increase of more than
     * {@code allowedLeak} live threads.
     *
     * <p>Useful to verify plugins clean up background threads on stop.
     */
    public static void assertNoThreadLeak(int allowedLeak, Runnable action) {
        int before = Thread.activeCount();
        action.run();
        // Allow threads to wind down
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int after = Thread.activeCount();
        if (after - before > allowedLeak) {
            Assertions.fail("Thread leak detected: " + before + " threads before → "
                    + after + " after (allowed leak: " + allowedLeak + ")");
        }
    }

    /** Poll a condition up to {@code timeoutMs}, failing if it never becomes true. */
    public static void assertEventually(String description, long timeoutMs, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        Assertions.fail("Condition never became true within " + timeoutMs + "ms: " + description);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
