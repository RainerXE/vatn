package dev.vatn.api;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing plugin updates and distribution.
 * Supports background delta updates for both metadata and binaries.
 */
@VatnApi(since = "1.0")
public interface VUpdateService extends VService {

    /**
     * Checks if a newer version of the plugin is available at the launch-spec update URL.
     */
    CompletableFuture<UpdateCheckResult> checkForUpdates(VPluginDescriptor descriptor);

    /**
     * downloads and verifies a new package, applying it on the next launch.
     */
    CompletableFuture<Boolean> downloadUpdate(VPluginDescriptor descriptor, URI updateLocation);

    /**
     * Describes the result of an update check.
     */
    record UpdateCheckResult(
        boolean updateAvailable,
        String latestVersion,
        String changelog,
        URI updateLocation,
        boolean requiresRestart
    ) {
        public static final UpdateCheckResult NO_UPDATE = new UpdateCheckResult(false, null, null, null, false);
    }
}
