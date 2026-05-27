package dev.vatn.core.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VSsrfGuard provides platform-level SSRF (Server-Side Request Forgery) protection.
 * It extracts potential hostnames/URLs from arbitrary text (tool arguments) and
 * validates that they do not resolve to private, loopback, or metadata IP ranges.
 */
public class VSsrfGuard {
    private static final Logger LOGGER = Logger.getLogger(VSsrfGuard.class.getName());

    // Matches: (1) schemed URLs with named/IP host (incl. single-label like localhost),
    // (2) schemed URLs with IPv6 bracket notation [::1], (3) bare multi-label hostnames,
    // (4) bare IPv4 addresses.
    private static final Pattern URL_PATTERN = Pattern.compile(
        "[a-zA-Z][a-zA-Z0-9+.-]*://(?:[a-zA-Z0-9-]+\\.)*[a-zA-Z0-9-]+" +
        "|[a-zA-Z][a-zA-Z0-9+.-]*://\\[[0-9a-fA-F:]+\\]" +
        "|(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}" +
        "|(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?",
        Pattern.CASE_INSENSITIVE
    );

    private static final long DEFAULT_TTL_MS = 60_000; // 1 minute
    private final Map<String, CacheEntry> dnsCache = new ConcurrentHashMap<>();

    /**
     * Checks if the provided text contains any SSRF attempts.
     *
     * @param text The text to scan (e.g., JSON tool arguments).
     * @return true if a blocked IP range is detected, false otherwise.
     */
    public boolean isSsrfAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            // Clean up: remove scheme if present
            String host = extractHost(candidate);
            
            if (isBlocked(host)) {
                return true;
            }
        }
        return false;
    }

    private String extractHost(String candidate) {
        String host = candidate;
        int schemeIdx = host.indexOf("://");
        if (schemeIdx != -1) {
            host = host.substring(schemeIdx + 3);
        }
        // IPv6 bracket notation: [::1] or [::1]:port
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            if (closing != -1) {
                return host.substring(1, closing);
            }
        }
        int pathIdx = host.indexOf('/');
        if (pathIdx != -1) {
            host = host.substring(0, pathIdx);
        }
        int portIdx = host.indexOf(':');
        if (portIdx != -1) {
            host = host.substring(0, portIdx);
        }
        return host;
    }

    private boolean isBlocked(String host) {
        // Block known dangerous hostnames before DNS resolution (covers envs where DNS fails)
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".internal") || lower.endsWith(".local")) {
            LOGGER.warning("BLOCK: SSRF attempt detected. Host [" + host + "] is a known dangerous hostname.");
            return true;
        }
        Set<InetAddress> addresses = resolve(host);
        for (InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                LOGGER.warning("BLOCK: SSRF attempt detected. Host [" + host + "] resolved to blocked IP [" + addr.getHostAddress() + "]");
                return true;
            }
        }
        return false;
    }

    private Set<InetAddress> resolve(String host) {
        long now = System.currentTimeMillis();
        CacheEntry entry = dnsCache.get(host);
        
        if (entry != null && (now - entry.timestamp) < DEFAULT_TTL_MS) {
            return entry.addresses;
        }

        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            Set<InetAddress> set = new HashSet<>();
            Collections.addAll(set, resolved);
            dnsCache.put(host, new CacheEntry(set, now));
            return set;
        } catch (UnknownHostException e) {
            // If we can't resolve it, we can't check it. 
            // Usually, this means it's not an SSRF threat via IP resolution.
            return Collections.emptySet();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "DNS Resolution failed for host: " + host, e);
            return Collections.emptySet();
        }
    }

    private boolean isBlockedAddress(InetAddress addr) {
        // Built-in checks
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return true;
        }

        byte[] bytes = addr.getAddress();
        
        // Manual IPv4 Checks for RFC 1918 (if isSiteLocalAddress isn't sufficient or consistent)
        if (bytes.length == 4) {
            int b1 = bytes[0] & 0xFF;
            int b2 = bytes[1] & 0xFF;

            // 10.0.0.0/8
            if (b1 == 10) return true;
            // 172.16.0.0/12
            if (b1 == 172 && (b2 >= 16 && b2 <= 31)) return true;
            // 192.168.0.0/16
            if (b1 == 192 && b2 == 168) return true;
            // 169.254.0.0/16 (Link Local, already checked but double-safe)
            if (b1 == 169 && b2 == 254) return true;
        }

        // IPv6 Unique Local Address (fc00::/7)
        if (bytes.length == 16) {
            if ((bytes[0] & 0xFE) == 0xFC) return true;
        }

        // AWS/GCP/Azure Metadata Endpoint: 169.254.169.254
        if (addr.getHostAddress().equals("169.254.169.254")) {
            return true;
        }

        return false;
    }

    private record CacheEntry(Set<InetAddress> addresses, long timestamp) {}
}
