package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Executes a single background job submitted to {@link VJobQueue}.
 *
 * <p>Unlike {@link VOperator}, a job handler has no type key — it is registered
 * directly against a job type string in {@code VJobQueueImpl.register()}.
 *
 * <p>Return the job's output string, or throw an exception to trigger the retry policy.
 */
@FunctionalInterface
@VatnApi(since = "1.0")
public interface VJobHandler {

    /**
     * @param ctx runtime context providing payload (via {@link VTaskContext#getConf()}),
     *            try number, and node services
     * @return job output (stored for retrieval via {@link VJobQueue#getResult}); may be null
     * @throws Exception on failure — the queue will apply the job's retry policy
     */
    String execute(VTaskContext ctx) throws Exception;
}
