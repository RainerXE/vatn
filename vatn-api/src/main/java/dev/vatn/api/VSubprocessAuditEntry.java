package dev.vatn.api;

import java.time.Instant;

/**
 * An immutable record of one subprocess execution recorded by {@link VSubprocessAuditService}.
 */
@VatnApi(since = "1.0-alpha.10")
public record VSubprocessAuditEntry(
    String  sessionId,
    String  command,
    int     exitCode,
    long    durationMs,
    Instant timestamp
) {
    /** Returns a compact JSON representation of this entry. */
    public String toJson() {
        String escapedCmd = command
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        return String.format(
            "{\"sessionId\":\"%s\",\"command\":\"%s\",\"exitCode\":%d,\"durationMs\":%d,\"timestamp\":\"%s\"}",
            sessionId, escapedCmd, exitCode, durationMs, timestamp
        );
    }
}
