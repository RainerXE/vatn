package dev.vatn.plugins.devenv;

import dev.vatn.api.VService;
import dev.vatn.plugins.devenv.model.DevEnvSnapshot;

import java.util.Optional;

/**
 * Typed node service exposing the developer-environment inventory.
 *
 * <p>Obtain via {@code ctx.getService(DevEnvService.class)}. Thread-safe.
 */
public interface DevEnvService extends VService {

    /** Run a full scan synchronously (scanners fan out across virtual threads). */
    DevEnvSnapshot scan();

    /** Most recent completed snapshot, or empty if none has completed yet. */
    Optional<DevEnvSnapshot> lastSnapshot();

    /** Trigger a background rescan and return immediately. */
    void refresh();

    /** Last snapshot if present, otherwise a fresh synchronous scan. */
    default DevEnvSnapshot snapshotOrScan() {
        return lastSnapshot().orElseGet(this::scan);
    }
}
