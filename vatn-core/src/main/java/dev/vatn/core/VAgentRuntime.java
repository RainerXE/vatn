package dev.vatn.core;

import dev.vatn.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages one {@link VAgent}'s lifecycle, heartbeat broadcasting, and role transitions.
 *
 * <p>Heartbeat protocol (ACTIVE_PASSIVE):
 * <ul>
 *   <li>PRIMARY publishes {@code vatn.agent.{id}.hb} every {@code heartbeatIntervalMs}.</li>
 *   <li>STANDBY watchdog fires every {@code heartbeatIntervalMs * 2}; if no heartbeat
 *       arrived within {@code failoverTimeoutMs}, STANDBY self-promotes.</li>
 *   <li>On graceful resign, PRIMARY publishes {@code vatn.agent.{id}.resign} so the
 *       standby promotes immediately without waiting for timeout.</li>
 * </ul>
 */
class VAgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(VAgentRuntime.class);

    private static final String CH_HB       = "vatn.agent.%s.hb";
    private static final String CH_RESIGN   = "vatn.agent.%s.resign";
    private static final String CH_PROMOTED = "vatn.agent.%s.promoted";

    private final VAgent agent;
    private final VAgentMode mode;
    private final VNodeContextImpl nodeCtx;

    private volatile VAgentRole role;
    private volatile boolean running = false;
    private volatile long lastHeartbeatReceivedAt = 0;

    private final List<Consumer<VAgentRole>> roleChangeHandlers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    VAgentRuntime(VAgent agent, VAgentMode mode, VNodeContextImpl nodeCtx) {
        this.agent = agent;
        this.mode = mode;
        this.nodeCtx = nodeCtx;
    }

    void start() {
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("vatn-agent-" + agent.getId());
            return t;
        });

        VAgentContext ctx = buildContext();

        switch (mode.strategy()) {
            case SINGLETON -> {
                role = VAgentRole.PRIMARY;
                launchAgent(ctx);
            }
            case ACTIVE_PASSIVE -> {
                role = VAgentRole.STANDBY;
                subscribeHeartbeatChannels(ctx);
                launchAgent(ctx);
                // After half the failover window, promote if we haven't seen a primary yet
                scheduler.schedule(() -> {
                    if (running && role == VAgentRole.STANDBY && lastHeartbeatReceivedAt == 0) {
                        promote(ctx);
                    }
                }, mode.failoverTimeoutMs() / 2, TimeUnit.MILLISECONDS);
            }
            case TWIN -> {
                role = VAgentRole.TWIN;
                launchAgent(ctx);
                startHeartbeatBroadcast();
            }
        }
    }

    void stop() {
        running = false;
        try {
            if (scheduler != null) scheduler.shutdownNow();
            if (role == VAgentRole.PRIMARY && mode.strategy() == VAgentMode.Strategy.ACTIVE_PASSIVE) {
                nodeCtx.getMessaging().publish(
                    CH_RESIGN.formatted(agent.getId()), nodeCtx.getNodeId().getBytes());
                agent.onDemoted();
            }
            agent.onStop();
            log.info("Agent {} stopped (role={})", agent.getId(), role);
        } catch (Exception e) {
            log.warn("Error stopping agent {}", agent.getId(), e);
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void launchAgent(VAgentContext ctx) {
        Thread.ofVirtual().name("vatn-agent-start-" + agent.getId()).start(() -> {
            try {
                agent.onStart(ctx);
            } catch (Exception e) {
                log.error("Agent {} onStart failed", agent.getId(), e);
            }
        });
    }

    private void subscribeHeartbeatChannels(VAgentContext ctx) {
        VMessaging bus = nodeCtx.getMessaging();

        bus.subscribe(CH_HB.formatted(agent.getId()), payload -> {
            lastHeartbeatReceivedAt = System.currentTimeMillis();
        });

        bus.subscribe(CH_RESIGN.formatted(agent.getId()), payload -> {
            if (running && role == VAgentRole.STANDBY) {
                log.info("Agent {} received resign signal — promoting to PRIMARY", agent.getId());
                promote(ctx);
            }
        });

        // Watchdog: fire every 2× heartbeat interval
        scheduler.scheduleAtFixedRate(() -> {
            if (!running || role != VAgentRole.STANDBY) return;
            long silentMs = System.currentTimeMillis() - lastHeartbeatReceivedAt;
            if (lastHeartbeatReceivedAt > 0 && silentMs > mode.failoverTimeoutMs()) {
                log.warn("Agent {} heartbeat silent for {} ms — promoting to PRIMARY",
                    agent.getId(), silentMs);
                promote(ctx);
            }
        }, mode.failoverTimeoutMs(), mode.heartbeatIntervalMs() * 2L, TimeUnit.MILLISECONDS);
    }

    private void promote(VAgentContext ctx) {
        if (role == VAgentRole.PRIMARY) return;
        role = VAgentRole.PRIMARY;
        log.info("Agent {} is now PRIMARY on node {}", agent.getId(), nodeCtx.getNodeId());
        roleChangeHandlers.forEach(h -> notifyHandler(h, VAgentRole.PRIMARY));
        nodeCtx.getMessaging().publish(
            CH_PROMOTED.formatted(agent.getId()), nodeCtx.getNodeId().getBytes());
        startHeartbeatBroadcast();
        try { agent.onPromoted(ctx); }
        catch (Exception e) { log.warn("Agent {} onPromoted failed", agent.getId(), e); }
    }

    private void startHeartbeatBroadcast() {
        scheduler.scheduleAtFixedRate(() -> {
            if (running && (role == VAgentRole.PRIMARY || role == VAgentRole.TWIN)) {
                nodeCtx.getMessaging().publish(
                    CH_HB.formatted(agent.getId()), new byte[]{1});
            }
        }, 0, mode.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void notifyHandler(Consumer<VAgentRole> handler, VAgentRole newRole) {
        Thread.ofVirtual().start(() -> {
            try { handler.accept(newRole); }
            catch (Exception e) { log.warn("Agent {} role-change handler threw", agent.getId(), e); }
        });
    }

    private VAgentContext buildContext() {
        return new VAgentContext() {
            @Override public VNodeContext nodeContext()          { return nodeCtx; }
            @Override public VAgentRole   getRole()             { return role; }
            @Override public String       getNodeId()           { return nodeCtx.getNodeId(); }

            @Override
            public void reportHealthy() {
                if (role == VAgentRole.PRIMARY || role == VAgentRole.TWIN) {
                    nodeCtx.getMessaging().publish(
                        CH_HB.formatted(agent.getId()), new byte[]{1});
                }
            }

            @Override
            public void resign() {
                if (role != VAgentRole.PRIMARY) return;
                log.info("Agent {} voluntarily resigning PRIMARY", agent.getId());
                nodeCtx.getMessaging().publish(
                    CH_RESIGN.formatted(agent.getId()), nodeCtx.getNodeId().getBytes());
                role = VAgentRole.STANDBY;
                roleChangeHandlers.forEach(h -> notifyHandler(h, VAgentRole.STANDBY));
                agent.onDemoted();
            }

            @Override
            public void onRoleChange(Consumer<VAgentRole> handler) {
                roleChangeHandlers.add(handler);
            }
        };
    }
}
