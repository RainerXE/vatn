package dev.vatn.core.workflow;

import dev.vatn.api.workflow.VDag;
import dev.vatn.api.workflow.VDagRun;
import dev.vatn.api.workflow.VDagRunState;
import dev.vatn.api.workflow.VSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory subscription manager for DAG run state changes.
 * Subscribers are notified synchronously from the engine's virtual thread.
 */
public class VSubscriptionImpl implements VSubscription {
    private static final Logger logger = LoggerFactory.getLogger(VSubscriptionImpl.class);

    /** subscriptionId → runId (for run-specific subscriptions) */
    private final Map<String, String> runSubs = new ConcurrentHashMap<>();
    /** subscriptionId → tag (for tag-based subscriptions) */
    private final Map<String, String> tagSubs = new ConcurrentHashMap<>();
    /** subscriptionId → handler */
    private final Map<String, Consumer<VDagRun>> handlers = new ConcurrentHashMap<>();

    private final VDagRegistryImpl registry;

    public VSubscriptionImpl(VDagRegistryImpl registry) {
        this.registry = registry;
    }

    @Override
    public String subscribeToRun(String runId, Consumer<VDagRun> handler) {
        String subId = UUID.randomUUID().toString();
        runSubs.put(subId, runId);
        handlers.put(subId, handler);
        logger.debug("[SUBSCRIPTION] Run subscription {} for run {}", subId, runId);
        return subId;
    }

    @Override
    public String subscribeByTag(String tag, Consumer<VDagRun> handler) {
        String subId = UUID.randomUUID().toString();
        tagSubs.put(subId, tag);
        handlers.put(subId, handler);
        logger.debug("[SUBSCRIPTION] Tag subscription {} for tag '{}'", subId, tag);
        return subId;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        runSubs.remove(subscriptionId);
        tagSubs.remove(subscriptionId);
        handlers.remove(subscriptionId);
    }

    /**
     * Called by the engine whenever a DAG run changes state.
     * Notifies matching run-ID and tag-based subscribers.
     */
    void notifyRunChange(String runId, String dagId, VDagRunState newState) {
        // Build a lightweight VDagRun shell for notification
        VDagRun notification = new VDagRun(runId, dagId, newState, null, null, null, false, Map.of());

        // Notify run-specific subscribers
        runSubs.forEach((subId, watchedRunId) -> {
            if (runId.equals(watchedRunId)) {
                try { handlers.get(subId).accept(notification); }
                catch (Exception e) { logger.warn("[SUBSCRIPTION] Handler {} threw on run change", subId, e); }
            }
        });

        // Notify tag-based subscribers by matching the dag's tags
        registry.getDag(dagId).ifPresent(dag -> {
            tagSubs.forEach((subId, tag) -> {
                if (dag.tags().contains(tag)) {
                    try { handlers.get(subId).accept(notification); }
                    catch (Exception e) { logger.warn("[SUBSCRIPTION] Handler {} threw on tag match", subId, e); }
                }
            });
        });
    }
}
