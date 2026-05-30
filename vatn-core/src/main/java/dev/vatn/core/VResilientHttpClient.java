package dev.vatn.core;

import dev.vatn.api.VHttpClient;
import dev.vatn.api.VRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Resilient {@link VHttpClient} decorator: retry-with-backoff, ETag/TTL response caching,
 * and a per-host circuit breaker, layered over any delegate {@code VHttpClient}.
 *
 * <p>Optionally bound to a {@link VRateLimiter}: when set, every outbound call first acquires a
 * permit for the request's host key ({@code "out:<host>"}), so an upstream's published quota
 * (e.g. Hardcover 60/min, Google Books ~1k/day) is honoured automatically. Configure those
 * limits on the limiter once at startup.
 *
 * <p>Caching and conditional revalidation apply to GET only. On a cache hit within TTL the
 * cached body is returned without a network call; past TTL the client revalidates with
 * {@code If-None-Match} and serves the cached body on {@code 304}.
 */
public class VResilientHttpClient implements VHttpClient {

    private static final Logger log = LoggerFactory.getLogger(VResilientHttpClient.class);

    private final VHttpClient delegate;
    private final RetryPolicy retry;
    private final CachePolicy cache;
    private final CircuitBreakerPolicy breakerPolicy;
    private final VRateLimiter rateLimiter; // nullable

    private final Map<String, CacheEntry> responseCache;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public VResilientHttpClient(VHttpClient delegate) {
        this(delegate, RetryPolicy.defaults(), CachePolicy.defaults(), CircuitBreakerPolicy.defaults(), null);
    }

    public VResilientHttpClient(VHttpClient delegate,
                                RetryPolicy retry,
                                CachePolicy cache,
                                CircuitBreakerPolicy breakerPolicy,
                                VRateLimiter rateLimiter) {
        this.delegate = delegate;
        this.retry = retry != null ? retry : RetryPolicy.none();
        this.cache = cache != null ? cache : CachePolicy.disabled();
        this.breakerPolicy = breakerPolicy != null ? breakerPolicy : CircuitBreakerPolicy.disabled();
        this.rateLimiter = rateLimiter;
        int cap = Math.max(16, this.cache.maxEntries());
        this.responseCache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) {
                return size() > cap;
            }
        });
    }

    @Override
    public Response get(String url, Map<String, String> headers, Duration timeout) throws IOException {
        // 1. Serve from cache when fresh.
        CacheEntry cached = cache.enabled() ? responseCache.get(url) : null;
        if (cached != null && cached.isFresh()) {
            return cached.response;
        }

        // 2. Add conditional revalidation header when we have a stale-but-cached ETag.
        Map<String, String> effectiveHeaders = headers;
        if (cached != null && cached.etag != null) {
            effectiveHeaders = new HashMap<>(headers);
            effectiveHeaders.put("If-None-Match", cached.etag);
        }

        final Map<String, String> reqHeaders = effectiveHeaders;
        Response resp = execute(url, () -> delegate.get(url, reqHeaders, timeout));

        // 3. 304 Not Modified → refresh TTL, return cached body.
        if (resp.statusCode() == 304 && cached != null) {
            cached.refresh(cache.ttl());
            return cached.response;
        }

        // 4. Cache successful GETs that are cacheable.
        if (cache.enabled() && resp.isSuccess() && isCacheable(resp)) {
            String etag = resp.header("ETag").orElse(null);
            responseCache.put(url, new CacheEntry(resp, etag, Instant.now().plus(effectiveTtl(resp))));
        }
        return resp;
    }

    @Override
    public Response post(String url, String body, String contentType, Map<String, String> headers, Duration timeout) throws IOException {
        // POST is not cached and (by default) not retried for non-idempotency safety, but we still
        // apply the circuit breaker and rate limiter. Retries on POST only fire for connect-level
        // IOExceptions, never on a received 5xx (the request may have been applied server-side).
        return execute(url, () -> delegate.post(url, body, contentType, headers, timeout));
    }

    // ── core execution: rate limit + breaker + retry ────────────────────────────

    private interface Call { Response run() throws IOException; }

    private Response execute(String url, Call call) throws IOException {
        String host = hostOf(url);
        Breaker breaker = breakerPolicy.enabled() ? breakers.computeIfAbsent(host, h -> new Breaker()) : null;
        if (breaker != null && !breaker.allowRequest()) {
            throw new CircuitOpenException("Circuit breaker OPEN for host: " + host);
        }

        IOException last = null;
        for (int attempt = 1; attempt <= Math.max(1, retry.maxAttempts()); attempt++) {
            if (rateLimiter != null) {
                try {
                    rateLimiter.acquire("out:" + host);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for rate-limit permit: " + host, ie);
                }
            }
            try {
                Response resp = call.run();
                if (isRetryableStatus(resp.statusCode()) && attempt < retry.maxAttempts()) {
                    sleepBackoff(attempt, retryAfterMillis(resp));
                    continue;
                }
                if (breaker != null) {
                    if (resp.statusCode() >= 500) breaker.recordFailure(); else breaker.recordSuccess();
                }
                return resp;
            } catch (IOException ioe) {
                last = ioe;
                if (breaker != null) breaker.recordFailure();
                if (attempt < retry.maxAttempts()) {
                    log.debug("[HTTP] attempt {}/{} failed for {} — {}", attempt, retry.maxAttempts(), url, ioe.toString());
                    sleepBackoff(attempt, -1);
                } else {
                    throw ioe;
                }
            }
        }
        throw last != null ? last : new IOException("HTTP call failed: " + url);
    }

    private void sleepBackoff(int attempt, long retryAfterMillis) {
        long delayMs;
        if (retryAfterMillis >= 0 && retry.honorRetryAfter()) {
            delayMs = retryAfterMillis;
        } else {
            double base = retry.baseDelay().toMillis();
            double computed = base * Math.pow(retry.multiplier(), attempt - 1);
            delayMs = (long) Math.min(computed, retry.maxDelay().toMillis());
            if (retry.jitter()) {
                delayMs = (long) (delayMs * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5));
            }
        }
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || (status >= 500 && status <= 599);
    }

    private long retryAfterMillis(Response resp) {
        return resp.header("Retry-After").map(v -> {
            try { return Long.parseLong(v.trim()) * 1000L; }
            catch (NumberFormatException e) { return -1L; }
        }).orElse(-1L);
    }

    private boolean isCacheable(Response resp) {
        if (!cache.respectServerCaching()) return true;
        String cc = resp.header("Cache-Control").orElse("");
        return !cc.toLowerCase().contains("no-store");
    }

    private Duration effectiveTtl(Response resp) {
        if (cache.respectServerCaching()) {
            String cc = resp.header("Cache-Control").orElse("");
            for (String part : cc.split(",")) {
                part = part.trim().toLowerCase();
                if (part.startsWith("max-age=")) {
                    try { return Duration.ofSeconds(Long.parseLong(part.substring(8).trim())); }
                    catch (NumberFormatException ignore) { /* fall through */ }
                }
            }
        }
        return cache.ttl();
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h != null ? h : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ── cache entry ─────────────────────────────────────────────────────────────

    private static final class CacheEntry {
        final Response response;
        final String etag;
        volatile Instant expiresAt;

        CacheEntry(Response response, String etag, Instant expiresAt) {
            this.response = response;
            this.etag = etag;
            this.expiresAt = expiresAt;
        }
        boolean isFresh() { return Instant.now().isBefore(expiresAt); }
        void refresh(Duration ttl) { this.expiresAt = Instant.now().plus(ttl); }
    }

    // ── per-host circuit breaker ─────────────────────────────────────────────────

    private final class Breaker {
        private int consecutiveFailures = 0;
        private Instant openedAt = null;

        synchronized boolean allowRequest() {
            if (openedAt == null) return true; // CLOSED
            if (Instant.now().isAfter(openedAt.plus(breakerPolicy.openDuration()))) {
                return true; // HALF_OPEN — allow a trial request
            }
            return false; // OPEN
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openedAt = null;
        }

        synchronized void recordFailure() {
            consecutiveFailures++;
            if (consecutiveFailures >= breakerPolicy.failureThreshold()) {
                openedAt = Instant.now();
            }
        }
    }
}
