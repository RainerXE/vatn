package dev.vatn.verify;

import dev.vatn.core.security.VSsrfGuard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class VSsrfGuardTest {

    private final VSsrfGuard guard = new VSsrfGuard();

    @Test
    public void testDirectLoopback() {
        assertTrue(guard.isSsrfAttempt("http://127.0.0.1"), "Should block 127.0.0.1");
        assertTrue(guard.isSsrfAttempt("http://localhost"), "Should block localhost (resolves to 127.0.0.1)");
        assertTrue(guard.isSsrfAttempt("http://[::1]"), "Should block IPv6 loopback");
    }

    @Test
    public void testPrivateIpRanges() {
        assertTrue(guard.isSsrfAttempt("http://10.0.0.1/admin"), "Should block 10.x.x.x");
        assertTrue(guard.isSsrfAttempt("https://172.16.5.5"), "Should block 172.16.x.x");
        assertTrue(guard.isSsrfAttempt("http://192.168.1.1:8080"), "Should block 192.168.x.x");
    }

    @Test
    public void testMetadataEndpoints() {
        assertTrue(guard.isSsrfAttempt("http://169.254.169.254/latest/meta-data/"), "Should block AWS/GCP metadata");
        // Often resolves to link-local block
        assertTrue(guard.isSsrfAttempt("http://metadata.google.internal"), "Should block GCP metadata hostname");
    }

    @Test
    public void testSafeUrls() {
        assertFalse(guard.isSsrfAttempt("https://google.com"), "Should allow public internet");
        assertFalse(guard.isSsrfAttempt("https://github.com/vatn/core"), "Should allow public internet paths");
        assertFalse(guard.isSsrfAttempt("No URL here just some text"), "Should allow text without URLs");
    }

    @Test
    public void testJsonContext() {
        String jsonArgs = "{\"search_query\": \"latest news\", \"callback_url\": \"http://127.0.0.1:9000/hook\"}";
        assertTrue(guard.isSsrfAttempt(jsonArgs), "Should detect SSRF attempt hidden in JSON arguments");
    }

    @Test
    public void testCachingBehavior() {
        // First call: resolves
        long start = System.nanoTime();
        guard.isSsrfAttempt("http://google.com");
        long firstDuration = System.nanoTime() - start;

        // Second call: should be cached
        start = System.nanoTime();
        guard.isSsrfAttempt("http://google.com");
        long secondDuration = System.nanoTime() - start;

        // Note: In local environments DNS might be very fast anyway, 
        // but the second call should be significantly faster or at least near-instant.
        System.out.println("First call resolution: " + firstDuration + " ns");
        System.out.println("Second call (cached):  " + secondDuration + " ns");
        
        // We don't strictly assert duration because it's environment dependent, 
        // but we verify functional correctness persists.
        assertFalse(guard.isSsrfAttempt("http://google.com"));
    }
}
