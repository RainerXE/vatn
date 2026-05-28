package dev.vatn.api;

import java.util.List;

/**
 * Append-only audit log for subprocess (sandbox) executions inside a VATN node.
 *
 * <p>Every call that reaches {@link VSandboxProvider#exec} or any tool that spawns
 * a subprocess through {@link VProcessService} should write one entry here.
 * This gives any VATN application a queryable, session-scoped execution history
 * without coupling to application-level memory or database schemas.
 *
 * <p>The default implementation keeps entries in memory for the lifetime of the node.
 * For persistent audit history, applications may register their own database-backed
 * implementation before node start.
 *
 * <p>Usage:
 * <pre>{@code
 * VSubprocessAuditService audit = ctx.getService(VSubprocessAuditService.class).orElseThrow();
 *
 * // After a subprocess completes:
 * audit.record(new VSubprocessAuditEntry(sessionId, command, exitCode, durationMs, Instant.now()));
 *
 * // Query from REST handler or CLI:
 * List<VSubprocessAuditEntry> all = audit.getAll();
 * }</pre>
 */
@VatnApi(since = "1.0-alpha.10")
public interface VSubprocessAuditService extends VService {

    /** Appends one execution record. Thread-safe. */
    void record(VSubprocessAuditEntry entry);

    /** Returns all recorded entries in insertion order. */
    List<VSubprocessAuditEntry> getAll();

    /** Returns entries recorded for the given session ID. */
    List<VSubprocessAuditEntry> getForSession(String sessionId);

    /** Returns the total number of recorded entries. */
    int size();

    /** Returns all entries serialised as a JSON array string. */
    String toJsonArray();
}
