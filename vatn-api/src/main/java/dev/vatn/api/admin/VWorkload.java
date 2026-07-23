package dev.vatn.api.admin;

import dev.vatn.api.VatnApi;
import java.time.Instant;
import java.util.Map;

/**
 * Represents a currently active job or execution on the VATN node.
 * This provides a unified admin view across completely different execution paradigms.
 */
@VatnApi(since = "1.0-alpha.15")
public record VWorkload(
    String id,
    String name,
    Type type,
    Status status,
    Instant startTime,
    Map<String, String> resourceUsage
) {
    public enum Type {
        WASM,
        CONTAINER,
        PROCESS,
        DAG_TASK,
        NATIVE
    }

    public enum Status {
        STARTING,
        RUNNING,
        PAUSED,
        STOPPING,
        STOPPED,
        FAILED
    }
}
