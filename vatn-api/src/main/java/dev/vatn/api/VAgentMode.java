package dev.vatn.api;

/**
 * Deployment strategy for a {@link VAgent}.
 *
 * <pre>{@code
 * // Single node — no coordination
 * ctx.registerAgent(new TelegramAgent(token), VAgentMode.singleton());
 *
 * // Primary + standby — auto-failover via VMessaging heartbeat
 * ctx.registerAgent(new RcsGateway(config), VAgentMode.activePassive()
 *         .withHeartbeatInterval(3_000)
 *         .withFailoverTimeout(10_000));
 *
 * // Both nodes active — deduplication is the agent's responsibility
 * ctx.registerAgent(new WebhookRelay(config), VAgentMode.twin());
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.6")
public final class VAgentMode {

    public enum Strategy {
        /** One node owns the channel. No standby coordination. */
        SINGLETON,
        /** Primary + standby. Standby auto-promotes when heartbeat is lost. */
        ACTIVE_PASSIVE,
        /** Both nodes run as active. State sync and deduplication are agent-managed. */
        TWIN
    }

    private final Strategy strategy;
    private final long heartbeatIntervalMs;
    private final long failoverTimeoutMs;

    private VAgentMode(Strategy strategy, long heartbeatIntervalMs, long failoverTimeoutMs) {
        this.strategy = strategy;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.failoverTimeoutMs = failoverTimeoutMs;
    }

    public static VAgentMode singleton() {
        return new VAgentMode(Strategy.SINGLETON, 5_000, 15_000);
    }

    public static VAgentMode activePassive() {
        return new VAgentMode(Strategy.ACTIVE_PASSIVE, 5_000, 15_000);
    }

    public static VAgentMode twin() {
        return new VAgentMode(Strategy.TWIN, 5_000, 15_000);
    }

    /** How often (ms) the primary broadcasts a heartbeat. Default 5 000 ms. */
    public VAgentMode withHeartbeatInterval(long ms) {
        return new VAgentMode(strategy, ms, failoverTimeoutMs);
    }

    /** How long (ms) the standby waits without a heartbeat before promoting itself. Default 15 000 ms. */
    public VAgentMode withFailoverTimeout(long ms) {
        return new VAgentMode(strategy, heartbeatIntervalMs, ms);
    }

    public Strategy strategy()             { return strategy; }
    public long heartbeatIntervalMs()      { return heartbeatIntervalMs; }
    public long failoverTimeoutMs()        { return failoverTimeoutMs; }
}
