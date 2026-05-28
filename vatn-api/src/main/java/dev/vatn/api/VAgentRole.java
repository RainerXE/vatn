package dev.vatn.api;

/**
 * Operational role of a {@link VAgent} within a cluster of nodes.
 *
 * <ul>
 *   <li>{@link #PRIMARY}  — owns the external channel; actively sends and receives.</li>
 *   <li>{@link #STANDBY}  — monitors the primary's heartbeat; takes over on failure.</li>
 *   <li>{@link #TWIN}     — mirrors state with another node; both are active, deduplication
 *                           is left to the agent implementation.</li>
 * </ul>
 */
@VatnApi(since = "1.0-alpha.6")
public enum VAgentRole {
    PRIMARY,
    STANDBY,
    TWIN
}
