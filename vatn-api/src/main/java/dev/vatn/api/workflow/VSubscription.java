package dev.vatn.api.workflow;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.util.function.Consumer;

/**
 * VATN 1.0 R2 — Subscribe to DAG run state changes by run ID or tag.
 *
 * <p>Provides a push-based alternative to polling {@link VDagEngine#getRunById}.
 * Mobile, desktop, and SSE consumers use this to receive live updates.
 */
@VatnApi(since = "1.0")
public interface VSubscription extends VService {

    /**
     * Subscribes to all state-change events for a specific DAG run.
     *
     * @param runId   the run ID to watch
     * @param handler called on every state transition; may be called from a virtual thread
     * @return a subscription ID that can be passed to {@link #unsubscribe}
     */
    String subscribeToRun(String runId, Consumer<VDagRun> handler);

    /**
     * Subscribes to all state-change events for DAG runs matching a tag.
     *
     * @param tag     the tag to filter on (matched against {@link VDag#tags()})
     * @param handler called on every state transition for any matching run
     * @return a subscription ID that can be passed to {@link #unsubscribe}
     */
    String subscribeByTag(String tag, Consumer<VDagRun> handler);

    /**
     * Cancels a subscription established via {@link #subscribeToRun} or {@link #subscribeByTag}.
     *
     * @param subscriptionId the ID returned when the subscription was created
     */
    void unsubscribe(String subscriptionId);
}
