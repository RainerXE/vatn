package dev.vatn.api.replication;

import dev.vatn.api.VatnApi;

import java.time.Duration;

/**
 * Configuration for a replicated set.
 *
 * <p>Build one with {@link #of(String)} and the fluent {@code with*} methods:
 * <pre>{@code
 * VReplicationConfig cfg = VReplicationConfig.of("library")
 *     .withDirection(VReplicationDirection.BIDIRECTIONAL)
 *     .withSyncInterval(Duration.ofSeconds(10))
 *     .withConflictResolver(VConflictResolver.lastWriteWins())
 *     .withFilter((change, peer) -> change.key().startsWith("public/")); // partial replication
 * }</pre>
 */
@VatnApi(since = "1.2")
public record VReplicationConfig(
        String name,
        VReplicationDirection direction,
        Duration syncInterval,
        VConflictResolver conflictResolver,
        VReplicationFilter filter
) {
    /** A bidirectional set with last-writer-wins, full replication, and a 15-second sync interval. */
    public static VReplicationConfig of(String name) {
        return new VReplicationConfig(
                name,
                VReplicationDirection.BIDIRECTIONAL,
                Duration.ofSeconds(15),
                VConflictResolver.lastWriteWins(),
                VReplicationFilter.all());
    }

    public VReplicationConfig withDirection(VReplicationDirection d) {
        return new VReplicationConfig(name, d, syncInterval, conflictResolver, filter);
    }

    public VReplicationConfig withSyncInterval(Duration interval) {
        return new VReplicationConfig(name, direction, interval, conflictResolver, filter);
    }

    public VReplicationConfig withConflictResolver(VConflictResolver resolver) {
        return new VReplicationConfig(name, direction, syncInterval, resolver, filter);
    }

    public VReplicationConfig withFilter(VReplicationFilter f) {
        return new VReplicationConfig(name, direction, syncInterval, conflictResolver, f);
    }
}
