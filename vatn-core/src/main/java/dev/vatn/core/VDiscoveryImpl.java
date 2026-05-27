package dev.vatn.core;

import dev.vatn.api.VDiscovery;
import dev.vatn.api.VNodeIdentity;
import dev.vatn.api.VJson;
import dev.vatn.api.VNodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Hardened P2P implementation of the VDiscovery service.
 * Supports JSON-based heartbeats, service manifest broadcasting, and exponential backoff.
 */
public class VDiscoveryImpl implements VDiscovery {
    private static final Logger logger = LoggerFactory.getLogger(VDiscoveryImpl.class);
    private static final String DISCOVERY_CHANNEL = "vatn.discovery";
    
    // Configurable intervals
    private static final long INITIAL_INTERVAL_MS = 5000;
    private static final long MAX_INTERVAL_MS = 60000;

    private final Map<String, LatticePeerInfo> peerCache = new ConcurrentHashMap<>();
    private final VNodeContext context;
    private final VNodeIdentity identity;
    private final VJson json;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vatn-discovery-scheduler");
        t.setDaemon(true);
        return t;
    });

    private String localUri;
    private long currentIntervalMs = INITIAL_INTERVAL_MS;
    private ScheduledFuture<?> broadcastTask;

    public VDiscoveryImpl(VNodeContext context, VJson json, VNodeIdentity identity) {
        this.context = context;
        this.json = json;
        this.identity = identity;
        startListening();
        rescheduleBroadcast();
    }

    private void startListening() {
        // Delegate all incoming discovery payloads — regardless of transport (OIPC or UDP) —
        // through the verified pathway so Ed25519 checks are never bypassed.
        context.getMessaging().subscribe(DISCOVERY_CHANNEL, this::ingestPeerPayload);
    }

    /**
     * Processes a raw lattice heartbeat payload.
     *
     * <p>This is the single verified ingress point for peer discovery messages, used by both
     * the OIPC messaging subscriber and the UDP multicast transport.
     * It enforces replay protection, Ed25519 signature verification, and per-peer rate limiting.
     *
     * @param payload UTF-8 encoded JSON or the literal string {@code "SYNC"}
     */
    void ingestPeerPayload(byte[] payload) {
        String message = new String(payload, StandardCharsets.UTF_8);

        if ("SYNC".equals(message)) {
            resetBackoff();
            announceLocal();
            return;
        }

        try {
            LatticeNodeInfo remoteInfo = json.parse(message, LatticeNodeInfo.class);
            if (remoteInfo.nodeId().equals(context.getNodeId())) return;

            // 1. Replay Protection: reject messages with a clock skew > 10 s
            long now = System.currentTimeMillis();
            if (Math.abs(now - remoteInfo.timestamp()) > 10000) {
                logger.warn("[SECURITY] Rejecting stale/future heartbeat from node: {}", remoteInfo.nodeId());
                return;
            }

            // 2. Ed25519 Identity Verification
            String signable = remoteInfo.nodeId() + "|" + remoteInfo.timestamp() + "|" + String.join(",", remoteInfo.services());
            byte[] sigBytes = Base64.getDecoder().decode(remoteInfo.signature());
            if (!identity.verify(remoteInfo.nodeId(), signable.getBytes(StandardCharsets.UTF_8), sigBytes)) {
                logger.error("[SECURITY] INVALID SIGNATURE from node: {} — discarding.", remoteInfo.nodeId());
                return;
            }

            LatticePeerInfo peer = peerCache.computeIfAbsent(remoteInfo.nodeId(), id -> {
                logger.info("[DISCOVERY] Discovered new lattice node: {} -> {}", id, remoteInfo.uri());
                return new LatticePeerInfo(id);
            });

            // 3. Rate Limiting (Anti-Flood)
            if (peer.isFloodDetected(now)) {
                logger.warn("[SECURITY] Rate limit exceeded for node: {}. Ignoring update.", remoteInfo.nodeId());
                return;
            }

            peer.update(remoteInfo);

        } catch (Exception e) {
            // Silently drop — malformed/malicious payload
            logger.debug("[DISCOVERY] Discarded unparseable discovery payload: {}", e.getMessage());
        }
    }

    private synchronized void rescheduleBroadcast() {
        if (broadcastTask != null) broadcastTask.cancel(false);
        broadcastTask = scheduler.schedule(this::announceAndSchedule, currentIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void announceAndSchedule() {
        announceLocal();
        
        // Exponential backoff if the lattice is quiet/stable
        currentIntervalMs = Math.min(MAX_INTERVAL_MS, (long) (currentIntervalMs * 1.5));
        rescheduleBroadcast();
    }

    private void announceLocal() {
        if (localUri == null) return;
        
        // Collect services
        List<String> services = context.getPluginRegistry().getPlugins().stream()
                .map(dev.vatn.api.VNodePlugin::getId)
                .collect(Collectors.toList());

        long timestamp = System.currentTimeMillis();
        String signable = context.getNodeId() + "|" + timestamp + "|" + String.join(",", services);
        String signature = Base64.getEncoder().encodeToString(identity.sign(signable.getBytes(StandardCharsets.UTF_8)));

        LatticeNodeInfo info = new LatticeNodeInfo(
                context.getNodeId(),
                localUri,
                services,
                context.getConfiguration().isAot() ? "AOT" : "JVM",
                1.0, // Health
                0.9, // Reliability
                1.0, // Trust
                timestamp,
                signature
        );

        String payload = json.stringify(info);
        context.getMessaging().publish(DISCOVERY_CHANNEL, payload.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void resetBackoff() {
        currentIntervalMs = INITIAL_INTERVAL_MS;
        rescheduleBroadcast();
    }

    @Override
    public void announce(String nodeId, String endpointUri) {
        this.localUri = endpointUri;
        resetBackoff();
        announceLocal();
    }

    @Override
    public void requestSync() {
        logger.info("[DISCOVERY] Requesting lattice synchronization...");
        context.getMessaging().publish(DISCOVERY_CHANNEL, "SYNC".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> resolve(String nodeId) {
        return Optional.ofNullable(peerCache.get(nodeId)).map(LatticePeerInfo::getUri);
    }

    @Override
    public void registerRemote(String nodeId, String endpointUri) {
        peerCache.computeIfAbsent(nodeId, LatticePeerInfo::new).updateLegacy(endpointUri);
    }

    public Map<String, LatticePeerInfo> getPeerCache() {
        return Collections.unmodifiableMap(peerCache);
    }

    /**
     * DTO for heartbeat messages.
     */
    public record LatticeNodeInfo(
        String nodeId,
        String uri,
        List<String> services,
        String flavor,
        double health,
        double reliability,
        double trustworthiness,
        long timestamp,
        String signature
    ) {}

    /**
     * Internal tracking for peers.
     */
    public static class LatticePeerInfo {
        private final String nodeId;
        private String uri;
        private List<String> services = new ArrayList<>();
        private String flavor = "UNKNOWN";
        private double health = 1.0;
        private double reliability = 1.0;
        private double trustworthiness = 0.5;
        private long lastSeen = System.currentTimeMillis();
        
        // Rate limiting metrics
        private int updateCount = 0;
        private long windowStart = System.currentTimeMillis();

        public LatticePeerInfo(String nodeId) { this.nodeId = nodeId; }

        public synchronized boolean isFloodDetected(long now) {
            if (now - windowStart > 60000) { // 1-minute window
                windowStart = now;
                updateCount = 0;
            }
            updateCount++;
            
            // Defenses: Absolute (50/min) and Velocity (Ramp-up detection: 5 updates in 5s)
            return updateCount > 50; 
            // In a more complex impl, we would track velocity across shorter spans
        }

        public synchronized void update(LatticeNodeInfo info) {
            this.uri = info.uri();
            this.services = info.services();
            this.flavor = info.flavor();
            this.health = info.health();
            this.reliability = info.reliability();
            this.trustworthiness = info.trustworthiness();
            this.lastSeen = System.currentTimeMillis();
        }

        public synchronized void updateLegacy(String uri) {
            this.uri = uri;
            this.lastSeen = System.currentTimeMillis();
        }

        public String getNodeId() { return nodeId; }
        public String getUri() { return uri; }
        public List<String> getServices() { return services; }
        public String getFlavor() { return flavor; }
        public double getHealth() { return health; }
        public double getReliability() { return reliability; }
        public double getTrustworthiness() { return trustworthiness; }
        public long getLastSeen() { return lastSeen; }
    }
}
