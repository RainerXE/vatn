package dev.vatn.core;

import dev.vatn.api.VHttpClient;
import dev.vatn.api.VHttpClient.CachePolicy;
import dev.vatn.api.VHttpClient.CircuitBreakerPolicy;
import dev.vatn.api.VHttpClient.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VResilientHttpClientTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Stub that returns a fixed response every call. */
    private static VHttpClient fixed(int status, String body) {
        return fixed(status, body, Map.of());
    }

    private static VHttpClient fixed(int status, String body, Map<String, List<String>> headers) {
        return new VHttpClient() {
            @Override public VHttpClient.Response get(String url, Map<String, String> h, Duration t) {
                return new VHttpClient.Response(status, body, headers);
            }
            @Override public VHttpClient.Response post(String url, String b, String ct, Map<String, String> h, Duration t) {
                return new VHttpClient.Response(status, body, headers);
            }
        };
    }

    /** Stub that throws on the first N calls, then succeeds. */
    private static VHttpClient failThenSucceed(int failCount, String successBody) {
        AtomicInteger calls = new AtomicInteger();
        return new VHttpClient() {
            @Override public VHttpClient.Response get(String url, Map<String, String> h, Duration t) throws IOException {
                if (calls.incrementAndGet() <= failCount) throw new IOException("transient");
                return new VHttpClient.Response(200, successBody, Map.of());
            }
            @Override public VHttpClient.Response post(String url, String b, String ct, Map<String, String> h, Duration t) throws IOException {
                return get(url, h, t);
            }
        };
    }

    /** Stub that counts invocations. */
    private static class CountingDelegate implements VHttpClient {
        final AtomicInteger count = new AtomicInteger();
        final int returnStatus;
        CountingDelegate(int returnStatus) { this.returnStatus = returnStatus; }

        @Override public VHttpClient.Response get(String url, Map<String, String> h, Duration t) {
            count.incrementAndGet();
            return new VHttpClient.Response(returnStatus, "body", Map.of());
        }
        @Override public VHttpClient.Response post(String url, String b, String ct, Map<String, String> h, Duration t) {
            count.incrementAndGet();
            return new VHttpClient.Response(returnStatus, "body", Map.of());
        }
    }

    // ── retry ─────────────────────────────────────────────────────────────────

    @Test
    void retriesOnIOException() throws Exception {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50), 2.0, false, false);
        VResilientHttpClient client = new VResilientHttpClient(
                failThenSucceed(2, "ok"), policy, CachePolicy.disabled(), CircuitBreakerPolicy.disabled(), null);

        VHttpClient.Response resp = client.get("http://example.com");
        assertTrue(resp.isSuccess());
        assertEquals("ok", resp.body());
    }

    @Test
    void retriesOn5xx() throws Exception {
        CountingDelegate delegate = new CountingDelegate(503);
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(10), 1.0, false, false);
        VResilientHttpClient client = new VResilientHttpClient(
                delegate, policy, CachePolicy.disabled(), CircuitBreakerPolicy.disabled(), null);

        client.get("http://example.com");
        assertEquals(3, delegate.count.get(), "should attempt maxAttempts times");
    }

    @Test
    void doesNotRetryOn2xx() throws Exception {
        CountingDelegate delegate = new CountingDelegate(200);
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(10), 1.0, false, false);
        VResilientHttpClient client = new VResilientHttpClient(
                delegate, policy, CachePolicy.disabled(), CircuitBreakerPolicy.disabled(), null);

        client.get("http://example.com");
        assertEquals(1, delegate.count.get(), "success should not be retried");
    }

    // ── response cache ────────────────────────────────────────────────────────

    @Test
    void cacheServesSameResponseWithoutNetworkCall() throws Exception {
        CountingDelegate delegate = new CountingDelegate(200);
        CachePolicy cache = new CachePolicy(true, Duration.ofMinutes(5), 128, false);
        VResilientHttpClient client = new VResilientHttpClient(
                delegate, RetryPolicy.none(), cache, CircuitBreakerPolicy.disabled(), null);

        client.get("http://example.com/resource");
        client.get("http://example.com/resource");
        assertEquals(1, delegate.count.get(), "second call must be served from cache");
    }

    @Test
    void cacheRespects304NotModifiedWithEtag() throws Exception {
        // First call returns 200 with ETag
        AtomicInteger callCount = new AtomicInteger();
        VHttpClient etag = new VHttpClient() {
            @Override public VHttpClient.Response get(String url, Map<String, String> headers, Duration t) {
                int n = callCount.incrementAndGet();
                if (n == 1) return new VHttpClient.Response(200, "original", Map.of("ETag", List.of("\"abc\"")));
                // second call (revalidation) returns 304
                return new VHttpClient.Response(304, "", Map.of());
            }
            @Override public VHttpClient.Response post(String u, String b, String ct, Map<String, String> h, Duration t) {
                return new VHttpClient.Response(200, "", Map.of());
            }
        };

        // TTL of 0 so cache is immediately stale, forcing revalidation on 2nd call
        CachePolicy cache = new CachePolicy(true, Duration.ZERO, 128, false);
        VResilientHttpClient client = new VResilientHttpClient(
                etag, RetryPolicy.none(), cache, CircuitBreakerPolicy.disabled(), null);

        client.get("http://example.com/item");
        VHttpClient.Response resp = client.get("http://example.com/item"); // revalidate

        assertEquals(2, callCount.get());
        assertEquals("original", resp.body(), "cached body served on 304");
    }

    // ── circuit breaker ───────────────────────────────────────────────────────

    @Test
    void circuitBreakerOpensAfterThreshold() throws Exception {
        CountingDelegate delegate = new CountingDelegate(500);
        CircuitBreakerPolicy breaker = new CircuitBreakerPolicy(true, 3, Duration.ofSeconds(10));
        RetryPolicy noRetry = RetryPolicy.none();
        VResilientHttpClient client = new VResilientHttpClient(
                delegate, noRetry, CachePolicy.disabled(), breaker, null);

        // Trip the breaker
        for (int i = 0; i < 3; i++) client.get("http://example.com");

        // Next call should be rejected by open circuit
        assertThrows(VHttpClient.CircuitOpenException.class,
                () -> client.get("http://example.com"));
        assertEquals(3, delegate.count.get(), "no further calls after circuit opens");
    }

    @Test
    void circuitBreakerResetsAfterSuccess() throws Exception {
        // Use a delegate that fails twice then succeeds
        VHttpClient recovering = failThenSucceed(2, "recovered");
        CircuitBreakerPolicy breaker = new CircuitBreakerPolicy(true, 2, Duration.ofMillis(1));
        VResilientHttpClient client = new VResilientHttpClient(
                recovering, RetryPolicy.none(), CachePolicy.disabled(), breaker, null);

        // Trip it
        try { client.get("http://example.com"); } catch (IOException ignored) {}
        try { client.get("http://example.com"); } catch (IOException ignored) {}

        Thread.sleep(10); // wait for open duration to expire → HALF_OPEN trial
        VHttpClient.Response resp = client.get("http://example.com");
        assertTrue(resp.isSuccess(), "successful trial should reset the breaker");
    }

    // ── response header access ────────────────────────────────────────────────

    @Test
    void responseHeaderLookupIsCaseInsensitive() {
        VHttpClient.Response r = new VHttpClient.Response(200, "body",
                Map.of("Content-Type", List.of("application/json")));
        assertEquals("application/json", r.header("content-type").orElseThrow());
        assertEquals("application/json", r.header("CONTENT-TYPE").orElseThrow());
    }

    @Test
    void backCompatConstructorHasNoHeaders() {
        VHttpClient.Response r = new VHttpClient.Response(200, "body");
        assertTrue(r.header("anything").isEmpty());
        assertTrue(r.isSuccess());
    }
}
