package dev.vatn.api.workflow;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;

import java.util.List;

/**
 * SPI for cron/interval-based DAG scheduling.
 *
 * <p>The scheduler polls registered DAG schedules and triggers runs via
 * {@link VDagEngine} when the cron expression fires.
 *
 * <p>It also handles catchup runs for DAGs with {@link VDag#catchUp()} = true.
 */
@VatnApi(since = "1.0")
public interface VDagScheduler extends VService {

    /**
     * Starts the scheduler background loop.
     * Safe to call multiple times — subsequent calls are no-ops if already running.
     */
    void start();

    /**
     * Stops the scheduler. In-flight triggered runs are not affected.
     */
    void stop();

    /**
     * Registers or updates a cron schedule for a DAG.
     * Overrides the schedule embedded in {@link VDag#schedule()}.
     *
     * @param dagId          the DAG to schedule
     * @param cronExpression standard 5-field cron expression (minute hour day month weekday)
     */
    void schedule(String dagId, String cronExpression);

    /**
     * Removes the schedule for a DAG (will no longer be triggered automatically).
     *
     * @param dagId the DAG to unschedule
     */
    void unschedule(String dagId);

    /**
     * Lists all DAG IDs that currently have an active schedule.
     */
    List<String> listScheduled();

    /**
     * Returns true if the scheduler background loop is running.
     */
    boolean isRunning();
}
