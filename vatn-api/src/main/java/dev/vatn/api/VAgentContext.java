package dev.vatn.api;

import java.util.function.Consumer;

/**
 * Runtime context provided to a {@link VAgent} when it starts.
 *
 * <p>Gives the agent access to the full node context, its current role,
 * and coordination primitives (resign, role-change notifications).
 *
 * <pre>{@code
 * public void onStart(VAgentContext ctx) {
 *     // Pre-connect even as STANDBY so failover is instant
 *     connection = openConnection(config);
 *
 *     ctx.onRoleChange(role -> {
 *         if (role == VAgentRole.PRIMARY)  connection.startReceiving();
 *         else                             connection.pauseReceiving();
 *     });
 *
 *     if (ctx.isPrimary()) connection.startReceiving();
 * }
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.6")
public interface VAgentContext {

    /** Full access to VATN services, HTTP routing, health checks, etc. */
    VNodeContext nodeContext();

    /** Current role of this agent instance. Changes dynamically on failover. */
    VAgentRole getRole();

    /** Identifier of the local node running this agent. */
    String getNodeId();

    /** Shorthand for {@code getRole() == VAgentRole.PRIMARY}. */
    default boolean isPrimary() { return getRole() == VAgentRole.PRIMARY; }

    /**
     * Signals to the runtime that this agent is functioning correctly.
     * For PRIMARY agents, this also extends the heartbeat immediately rather than
     * waiting for the next scheduled interval — useful after processing a batch.
     * No-op for STANDBY or SINGLETON.
     */
    void reportHealthy();

    /**
     * Voluntarily steps down as PRIMARY. The standby will receive a resign signal
     * and promote itself. This agent transitions to STANDBY.
     * No-op if already STANDBY or SINGLETON.
     */
    void resign();

    /**
     * Registers a handler that is called whenever this agent's role changes.
     * Handlers are called on a virtual thread and must be thread-safe.
     *
     * @param handler receives the new role after each transition
     */
    void onRoleChange(Consumer<VAgentRole> handler);
}
