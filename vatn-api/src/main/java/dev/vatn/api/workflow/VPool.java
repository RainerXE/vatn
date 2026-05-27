package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * A named concurrency pool that limits how many tasks can run simultaneously.
 * Tasks claiming a pool slot block if all slots are occupied.
 *
 * @param name        Unique pool identifier (e.g., {@code "heavy-compute"}).
 * @param slots       Maximum concurrent task instances allowed in this pool.
 * @param description Human-readable description.
 */
@VatnApi(since = "1.0")
public record VPool(String name, int slots, String description) {

    /** Built-in default pool used when no pool is specified on a task. */
    public static final String DEFAULT_POOL = "default_pool";
    public static final int DEFAULT_POOL_SLOTS = 128;
}
