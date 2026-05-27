package dev.vatn.core;

import dev.vatn.api.VMessaging;
import dev.vatn.api.VStream;
import dev.vatn.api.VatnSecurity;
import dev.vatn.api.security.PolicyViolation;
import dev.vatn.api.security.VFlowPolicy;
import dev.vatn.api.security.VPolicyInterjector;
import dev.vatn.api.security.VTrustLevel;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic implementation of the VStream service.
 * Supports pipe operations using Virtual Threads.
 */
public class VStreamServiceImpl implements VStream {

    private static final Logger logger = LoggerFactory.getLogger(VStreamServiceImpl.class);
    private static final String SECURITY_CHANNEL = "vatn.monitor.security";

    private final Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();
    private final List<VPolicyInterjector> interjectors = new CopyOnWriteArrayList<>();
    private final VRegistry registry;
    private final VMessaging messaging;

    public VStreamServiceImpl(VRegistry registry, VMessaging messaging) {
        this.registry = registry;
        this.messaging = messaging;
    }

    public void addInterjector(VPolicyInterjector interjector) {
        interjectors.add(interjector);
    }

    @Override
    public OutputStream createPolicyStream(String streamId, VFlowPolicy policy) {
        // 1. "Most Restrictive Wins" Policy Resolution
        // Retrieve caller ID from ScopedValue (propagated by VNodeRunner)
        String callerId = VatnSecurity.CURRENT_PLUGIN_ID.isBound() 
            ? VatnSecurity.CURRENT_PLUGIN_ID.get() 
            : "unknown-plugin"; 
        
        // Check Registry Trust Level
        VTrustLevel trust = registry.getTrustLevel(callerId);
        if (policy.requiredTrust().ordinal() > trust.ordinal()) {
            throw new SecurityException("Plugin " + callerId + " trust level (" + trust + ") insufficient for policy: " + policy);
        }

        // Consult Interjectors
        boolean allowed = false;
        for (VPolicyInterjector interjector : interjectors) {
            VPolicyInterjector.Decision decision = interjector.onFlowRequest(callerId, policy);
            if (decision == VPolicyInterjector.Decision.DENY) {
                PolicyViolation violation = new PolicyViolation(
                    interjector.getClass().getSimpleName(), callerId, streamId, policy.mode().name());
                messaging.publish(SECURITY_CHANNEL, violation.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                throw new SecurityException("Flow " + streamId + " explicitly denied by policy.");
            }
            if (decision == VPolicyInterjector.Decision.ALLOW) {
                allowed = true;
            }
        }

        // Default Deny if no one allowed it and interjectors are registered
        if (!allowed && !interjectors.isEmpty()) {
            throw new SecurityException("Flow " + streamId + " rejected (No interjector allowed it).");
        }

        logger.debug("[VStream] Creating policy-compliant stream: {} Mode: {}", streamId, policy.mode());
        return createOutput(streamId);
    }

    @Override
    public OutputStream createOutput(String streamId) {
        try {
            // Increase buffer size to 64KB for smoother high-throughput piping
            PipedOutputStream out = new PipedOutputStream();
            PipedInputStream in = new PipedInputStream(out, 64 * 1024);
            activeStreams.put(streamId, in);
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output stream: " + streamId, e);
        }
    }

    @Override
    public InputStream openInput(String streamId) {
        InputStream in = activeStreams.remove(streamId);
        if (in == null) {
            throw new RuntimeException("Stream not found: " + streamId);
        }
        return in;
    }

    @Override
    public void pipe(InputStream in, OutputStream out) {
        // Implementation of piping using Loom virtual threads for high efficiency
        Thread.ofVirtual().start(() -> {
            try (in; out) {
                in.transferTo(out);
            } catch (IOException e) {
                logger.error("[VStream] Piping error", e);
            }
        });
    }

    @Override
    public OutputStream createRemoteOutput(String targetUrl) {
        try {
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos, 64 * 1024);
            
            // Async relay to remote node
            Thread.ofVirtual().start(() -> {
                try (pis) {
                    logger.debug("[VStream] Starting remote relay as OutputStream to: {}", targetUrl);
                    io.helidon.webclient.api.WebClient client = io.helidon.webclient.api.WebClient.builder().build();
                    
                    io.helidon.webclient.api.HttpClientResponse response = null;
                    Exception lastEx = null;
                    
                    for (int r = 0; r < 5; r++) {
                        try {
                            response = client.put()
                                .uri(targetUrl)
                                .outputStream(out -> {
                                    try (out) {
                                        pis.transferTo(out);
                                        out.flush();
                                    }
                                });
                            break;
                        } catch (Exception e) {
                            lastEx = (Exception) e;
                            try { Thread.sleep(200); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
                        }
                    }
                    
                    if (response != null) {
                        logger.debug("[VStream] Remote relay status: {}", response.status());
                    } else if (lastEx != null) {
                        throw lastEx;
                    }
                } catch (Exception e) {
                    logger.error("[VStream] Remote relay fatal error to {}", targetUrl, e);
                }
            });
            
            return pos;
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize remote stream relay", e);
        }
    }

    /**
     * Internal method to register an incoming network stream for a plugin to consume.
     */
    public void ingest(String streamId, InputStream in) {
        activeStreams.put(streamId, in);
    }
}
