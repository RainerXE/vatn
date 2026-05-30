package dev.vatn.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight periodic task scheduler — cron expressions and fixed intervals, decoupled from
 * the DAG engine.
 *
 * <p>Where {@link dev.vatn.api.workflow.VDagScheduler} schedules full DAG runs, {@code VScheduler}
 * runs a plain {@link Runnable} on a virtual thread. It is the right tool for recurring scans,
 * cache refreshes, enrichment passes, and housekeeping that don't need a task graph.
 *
 * <pre>{@code
 * VScheduler scheduler = ctx.getService(VScheduler.class).orElseThrow();
 *
 * // Every night at 02:30 — re-run enrichment for stale records.
 * scheduler.cron("nightly-enrich", "30 2 * * *", () -> enrichmentService.runStale());
 *
 * // Every 15 minutes — refresh a remote catalogue.
 * scheduler.every("catalogue-refresh", Duration.ofMinutes(15), () -> catalogue.refresh());
 *
 * scheduler.cancel("catalogue-refresh");
 * }</pre>
 *
 * <p>Tasks are identified by a caller-supplied name; scheduling a task with an existing name
 * replaces it. Overlapping runs of the same task are skipped — a run is not started while the
 * previous run of the same name is still executing.
 */
@VatnApi(since = "1.2")
public interface VScheduler extends VService {

    /**
     * Schedules {@code task} to run on a standard 5-field cron expression
     * (minute hour day-of-month month day-of-week).
     *
     * @param name           unique task name; replaces any existing task with the same name
     * @param cronExpression 5-field cron expression
     * @param task           the work to run
     */
    void cron(String name, String cronExpression, Runnable task);

    /**
     * Schedules {@code task} to run repeatedly at a fixed interval. The first run occurs one
     * interval from registration.
     *
     * @param name     unique task name; replaces any existing task with the same name
     * @param interval the delay between the end of one run and the start of the next
     * @param task     the work to run
     */
    void every(String name, Duration interval, Runnable task);

    /**
     * Cancels a scheduled task. No-op if no task with that name exists.
     *
     * @param name the task name
     * @return true if a task was cancelled
     */
    boolean cancel(String name);

    /** Returns the names of all currently scheduled tasks. */
    List<String> scheduled();

    /** Returns the next fire time for a scheduled task, if known. */
    Optional<Instant> nextRun(String name);
}
