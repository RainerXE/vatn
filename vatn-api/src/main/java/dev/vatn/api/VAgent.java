package dev.vatn.api;

/**
 * Outbound agent SPI — a background component that owns an external communication
 * channel for the duration of the node's lifetime.
 *
 * <p>Typical use cases: Telegram bot, RCS gateway, webhook relay, email poller,
 * SMS provider connection, ActivityPub federation worker.
 *
 * <p>Agents are registered via {@link VNodeContext#registerAgent} before the node
 * starts. The runtime manages their lifecycle: {@link #onStart} is called after all
 * plugins are initialized; {@link #onStop} is called on graceful shutdown.
 *
 * <h3>Active-passive example (Telegram bot)</h3>
 * <pre>{@code
 * public class TelegramAgent implements VAgent {
 *
 *     private final String token;
 *     private TelegramClient client;
 *
 *     @Override public String getId()          { return "telegram-gateway"; }
 *     @Override public String getChannelType() { return "telegram"; }
 *
 *     @Override
 *     public void onStart(VAgentContext ctx) {
 *         client = TelegramClient.connect(token);
 *
 *         // Pre-connect even as STANDBY — failover is then instant
 *         ctx.onRoleChange(role -> {
 *             if (role == VAgentRole.PRIMARY) client.startPolling(this::handle);
 *             else                            client.stopPolling();
 *         });
 *
 *         if (ctx.isPrimary()) client.startPolling(this::handle);
 *     }
 *
 *     @Override public void onStop() { if (client != null) client.close(); }
 * }
 *
 * // Registration
 * ctx.registerAgent(new TelegramAgent(token),
 *         VAgentMode.activePassive().withFailoverTimeout(10_000));
 * }</pre>
 *
 * <h3>Twin example (RCS gateway with deduplication)</h3>
 * <pre>{@code
 * public class RcsAgent implements VAgent {
 *
 *     @Override public String getId()          { return "rcs-gateway"; }
 *     @Override public String getChannelType() { return "rcs"; }
 *
 *     @Override
 *     public void onStart(VAgentContext ctx) {
 *         // Both twins receive; use nodeId-based tiebreaker to decide who sends
 *         boolean isSender = ctx.getNodeId().compareTo(siblingId) < 0;
 *         gateway.connect(inbound -> {
 *             if (isSender) forward(inbound);
 *         });
 *     }
 * }
 *
 * ctx.registerAgent(new RcsAgent(config), VAgentMode.twin());
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.6")
public interface VAgent {

    /**
     * Unique identifier for this agent, stable across restarts.
     * Used as the key for heartbeat channels and service discovery.
     * Example: {@code "telegram-bot"}, {@code "rcs-gateway-eu"}.
     */
    String getId();

    /**
     * Human-readable type label for this channel, used in logs and health output.
     * Example: {@code "telegram"}, {@code "rcs"}, {@code "webhook"}, {@code "email"}.
     */
    default String getChannelType() { return "generic"; }

    /**
     * Called once by the runtime after all plugins are initialized.
     * The agent should open its external connection here.
     * For ACTIVE_PASSIVE standby agents, this is still called — agents
     * should pre-connect in standby mode so role promotion is instantaneous.
     *
     * @param ctx role-aware context; use {@link VAgentContext#onRoleChange} to react
     *            to primary/standby transitions
     */
    void onStart(VAgentContext ctx) throws Exception;

    /**
     * Called on graceful shutdown. Close connections and free resources.
     */
    default void onStop() {}

    /**
     * Called when this agent transitions from STANDBY to PRIMARY (failover or resign).
     * Implement to activate sending on a pre-connected channel.
     *
     * @param ctx updated context — {@code ctx.getRole()} is now {@code PRIMARY}
     */
    default void onPromoted(VAgentContext ctx) {}

    /**
     * Called when this agent voluntarily steps down from PRIMARY to STANDBY
     * via {@link VAgentContext#resign()}. Implement to pause outbound sending
     * without closing the connection.
     */
    default void onDemoted() {}
}
