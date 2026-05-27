package dev.vatn.api.memory;

import java.io.Serializable;

import dev.vatn.api.VatnApi;

/**
 * Protocol record for federated memory synchronization.
 * Contains operation details, high-precision timestamps for conflict resolution,
 * and a GZIP-compressed payload.
 */
@VatnApi(since = "1.0")
public record VMemoryEvent(
    String op,         // UPSERT, DELETE, STATE_PULL
    String type,       // FACT, JSON, MESSAGE
    String workspaceId,
    long timestamp,    // System.currentTimeMillis() or high-res clock
    byte[] payload     // GZIP compressed serialized data
) implements Serializable {}
