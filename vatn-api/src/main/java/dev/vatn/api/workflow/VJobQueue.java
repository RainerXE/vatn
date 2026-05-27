package dev.vatn.api.workflow;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * VATN 1.0 R2 — Generic background job queue for lightweight, non-DAG tasks.
 *
 * <p>Use {@link VDagEngine} for complex multi-step workflows; use {@code VJobQueue}
 * for simple fire-and-monitor background operations.
 *
 * <p>Jobs are identified by an {@code idempotencyKey}: submitting the same key twice
 * within the job's TTL returns the existing job ID instead of creating a duplicate.
 */
@VatnApi(since = "1.0")
public interface VJobQueue extends VService {

    /**
     * Submits a job.
     *
     * @param jobType         registered job type identifier
     * @param payload         key-value payload passed to the job handler
     * @param retryPolicy     retry configuration; use {@link VRetryPolicy#NONE} to disable
     * @param ttl             maximum time a queued job may wait before expiring; null = no TTL
     * @param idempotencyKey  unique key for deduplication; null = always create new job
     * @return the job ID (existing if idempotency key matched, new otherwise)
     */
    String submit(String jobType, Map<String, String> payload, VRetryPolicy retryPolicy,
                  Duration ttl, String idempotencyKey);

    /** Convenience: submit with no TTL, no idempotency key, and no retries. */
    default String submit(String jobType, Map<String, String> payload) {
        return submit(jobType, payload, VRetryPolicy.NONE, null, null);
    }

    /**
     * Retrieves the result/state of a submitted job.
     *
     * @param jobId the job ID returned by {@link #submit}
     * @return the result, or empty if the job ID is unknown
     */
    Optional<VTaskInstance> getResult(String jobId);

    /**
     * Cancels a queued or running job.
     *
     * @param jobId the job ID to cancel
     * @return true if the job was found and canceled
     */
    boolean cancel(String jobId);
}
