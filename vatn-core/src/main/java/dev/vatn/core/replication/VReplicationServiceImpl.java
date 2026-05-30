package dev.vatn.core.replication;

import dev.vatn.api.VPersistenceService;
import dev.vatn.api.VRpcException;
import dev.vatn.api.VRpcResponse;
import dev.vatn.api.VRpcService;
import dev.vatn.api.replication.VChange;
import dev.vatn.api.replication.VReplicatedSet;
import dev.vatn.api.replication.VReplicationConfig;
import dev.vatn.api.replication.VReplicationDirection;
import dev.vatn.api.replication.VReplicationFilter;
import dev.vatn.api.replication.VReplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Drives replicated sets over {@link VRpcService}: registers the {@code vatn.repl.pull} and
 * {@code vatn.repl.apply} handlers, and runs a periodic anti-entropy loop that, for each active set
 * and each known peer, transfers only the delta past that peer's watermark and applies it through
 * the set's conflict resolver.
 *
 * <p>Peers are enumerated via the injected supplier (wired to {@code VDiscovery}'s peer cache),
 * keeping this service decoupled from the discovery implementation.
 */
public class VReplicationServiceImpl implements VReplicationService {

    private static final Logger log = LoggerFactory.getLogger(VReplicationServiceImpl.class);
    private static final String M_PULL  = "vatn.repl.pull";
    private static final String M_APPLY = "vatn.repl.apply";
    private static final int BATCH_LIMIT = 500;

    private final String localNodeId;
    private final VPersistenceService db;
    private final VRpcService rpc;
    private final Supplier<Collection<String>> peersSupplier;

    private final ConcurrentHashMap<String, Registration> sets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public VReplicationServiceImpl(String localNodeId, VPersistenceService db, VRpcService rpc,
                                   Supplier<Collection<String>> peersSupplier) {
        this.localNodeId = localNodeId;
        this.db = db;
        this.rpc = rpc;
        this.peersSupplier = peersSupplier;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vatn-replication");
            t.setDaemon(true);
            return t;
        });
        registerRpcHandlers();
    }

    // ── service API ─────────────────────────────────────────────────────────────

    @Override
    public synchronized VReplicatedSet replicate(VReplicationConfig config) {
        Registration existing = sets.get(config.name());
        if (existing != null) return existing.set;

        VReplicatedSetImpl set = new VReplicatedSetImpl(config, localNodeId, db,
                () -> syncSet(config.name()));
        Duration interval = config.syncInterval() != null ? config.syncInterval() : Duration.ofSeconds(15);
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(
                () -> safeSync(config.name()),
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        sets.put(config.name(), new Registration(set, config, task));
        log.info("[REPL] Replicating set '{}' (direction={}, every {})",
                config.name(), config.direction(), interval);
        return set;
    }

    @Override
    public Optional<VReplicatedSet> get(String name) {
        Registration r = sets.get(name);
        return r == null ? Optional.empty() : Optional.of(r.set);
    }

    @Override
    public Collection<VReplicatedSet> active() {
        List<VReplicatedSet> out = new ArrayList<>();
        sets.values().forEach(r -> out.add(r.set));
        return out;
    }

    @Override
    public synchronized void stop(String name) {
        Registration r = sets.remove(name);
        if (r != null) {
            r.task.cancel(false);
            r.set.close();
            log.info("[REPL] Stopped replicating set '{}'", name);
        }
    }

    /** Stops all sync loops. */
    public void shutdown() {
        sets.values().forEach(r -> r.task.cancel(false));
        sets.clear();
        scheduler.shutdownNow();
    }

    // ── sync loop ─────────────────────────────────────────────────────────────

    private void safeSync(String setName) {
        try {
            syncSet(setName);
        } catch (Exception e) {
            log.warn("[REPL] Sync pass failed for set '{}'", setName, e);
        }
    }

    private void syncSet(String setName) {
        Registration reg = sets.get(setName);
        if (reg == null) return;
        Collection<String> peers = peersSupplier.get();
        if (peers == null || peers.isEmpty()) return;

        boolean doPull = reg.config.direction() != VReplicationDirection.PUSH;
        boolean doPush = reg.config.direction() != VReplicationDirection.PULL;

        for (String peer : peers) {
            if (peer == null || peer.equals(localNodeId)) continue;
            if (doPull) pullFrom(reg, peer);
            if (doPush) pushTo(reg, peer);
        }
        reg.set.markSynced();
    }

    /** Pull peer's changes after our inbound watermark and apply them. */
    private void pullFrom(Registration reg, String peer) {
        try {
            long after = reg.set.watermark(peer);
            byte[] req = ReplCodec.encodePullRequest(new ReplCodec.PullRequest(reg.config.name(), after, BATCH_LIMIT));
            VRpcResponse resp = rpc.call(peer, M_PULL, req, Duration.ofSeconds(10));
            if (!resp.ok()) {
                log.debug("[REPL] pull from {} for '{}' returned error: {}", peer, reg.config.name(), resp.errorMessage());
                return;
            }
            ReplCodec.Batch batch = ReplCodec.decodeBatch(resp.payload());
            if (batch.changes().isEmpty() && batch.throughOffset() <= after) return;
            int applied = reg.set.applyInbound(peer, batch.changes(), batch.throughOffset());
            if (applied > 0) {
                log.debug("[REPL] Applied {} change(s) from {} to set '{}'", applied, peer, reg.config.name());
            }
        } catch (VRpcException e) {
            log.debug("[REPL] pull from {} failed: {}", peer, e.getMessage());
        }
    }

    /** Push our changes after the outbound watermark to a peer that applies them. */
    private void pushTo(Registration reg, String peer) {
        try {
            String wmKey = "out:" + peer;
            long after = reg.set.watermark(wmKey);
            List<VChange> all = reg.set.changesSince(after, BATCH_LIMIT);
            long through = reg.set.maxSeqInWindow(after, BATCH_LIMIT);
            VReplicationFilter filter = reg.config.filter();
            List<VChange> filtered = new ArrayList<>(all.size());
            for (VChange c : all) {
                if (filter == null || filter.shouldReplicate(c, peer)) filtered.add(c);
            }
            if (filtered.isEmpty() && through <= after) return;

            byte[] payload = ReplCodec.encodeBatch(new ReplCodec.Batch(reg.config.name(), through, filtered));
            VRpcResponse resp = rpc.call(peer, M_APPLY, payload, Duration.ofSeconds(10));
            if (resp.ok()) {
                reg.set.incrementSent(filtered.size());
                reg.set.setWatermark(wmKey, through);
            }
        } catch (VRpcException e) {
            log.debug("[REPL] push to {} failed: {}", peer, e.getMessage());
        }
    }

    // ── RPC handlers (server side) ───────────────────────────────────────────────

    private void registerRpcHandlers() {
        // A peer asks us for our changes since its watermark; we filter for that peer.
        rpc.register(M_PULL, request -> {
            ReplCodec.PullRequest pr = ReplCodec.decodePullRequest(request.payload());
            Registration reg = sets.get(pr.set());
            if (reg == null) {
                return ReplCodec.encodeBatch(new ReplCodec.Batch(pr.set(), pr.afterOffset(), List.of()));
            }
            List<VChange> window = reg.set.changesSince(pr.afterOffset(), pr.limit());
            long through = reg.set.maxSeqInWindow(pr.afterOffset(), pr.limit());
            VReplicationFilter filter = reg.config.filter();
            List<VChange> filtered = new ArrayList<>(window.size());
            for (VChange c : window) {
                if (filter == null || filter.shouldReplicate(c, request.callerNodeId())) filtered.add(c);
            }
            reg.set.incrementSent(filtered.size());
            return ReplCodec.encodeBatch(new ReplCodec.Batch(pr.set(), through, filtered));
        });

        // A peer pushes changes to us; we apply them with conflict resolution.
        rpc.register(M_APPLY, request -> {
            ReplCodec.Batch batch = ReplCodec.decodeBatch(request.payload());
            Registration reg = sets.get(batch.set());
            if (reg == null) return new byte[]{0};
            int applied = reg.set.applyInbound(request.callerNodeId(), batch.changes(), 0L);
            return new byte[]{(byte) Math.min(applied, 127)};
        });
    }

    private record Registration(VReplicatedSetImpl set, VReplicationConfig config, ScheduledFuture<?> task) {}
}
