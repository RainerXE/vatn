package dev.vatn.api;

/**
 * A snapshot of a running {@link VAgent}'s identity and current role.
 * Returned by {@link VNodeContext#getAgentInfos()} — safe to serialize and log.
 *
 * @param id          agent identifier (e.g. {@code "comm.telegram"})
 * @param channelType human-readable type label (e.g. {@code "telegram"})
 * @param role        current role — changes dynamically on failover
 * @param strategy    deployment strategy configured at registration time
 */
@VatnApi(since = "1.0-alpha.7")
public record VAgentInfo(
        String         id,
        String         channelType,
        VAgentRole     role,
        VAgentMode.Strategy strategy
) {}
