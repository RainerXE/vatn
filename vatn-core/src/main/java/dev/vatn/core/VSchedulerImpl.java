package dev.vatn.core;

import dev.vatn.api.VScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Virtual-thread-backed implementation of {@link VScheduler}.
 *
 * <p>A single-threaded {@link ScheduledExecutorService} acts as the timing wheel; each fired task
 * is dispatched to its own virtual thread so a slow task never blocks the scheduler. Overlapping
 * runs of the same task are skipped via a per-task running flag.
 */
public class VSchedulerImpl implements VScheduler {

    private static final Logger log = LoggerFactory.getLogger(VSchedulerImpl.class);

    private final ScheduledExecutorService timer;
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    public VSchedulerImpl() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "vatn-scheduler");
            t.setDaemon(true);
            return t;
        };
        this.timer = Executors.newSingleThreadScheduledExecutor(tf);
    }

    @Override
    public void cron(String name, String cronExpression, Runnable runnable) {
        if (!CronEvaluator.isValid(cronExpression)) {
            throw new IllegalArgumentException("Invalid 5-field cron expression: " + cronExpression);
        }
        cancel(name);
        Task task = new Task(name, runnable, cronExpression, null);
        tasks.put(name, task);
        scheduleNextCron(task);
        log.info("[SCHEDULER] Registered cron task '{}' ({})", name, cronExpression);
    }

    @Override
    public void every(String name, Duration interval, Runnable runnable) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        cancel(name);
        Task task = new Task(name, runnable, null, interval);
        tasks.put(name, task);
        long ms = interval.toMillis();
        task.future = timer.scheduleWithFixedDelay(() -> fire(task), ms, ms, TimeUnit.MILLISECONDS);
        task.nextRun = Instant.now().plus(interval);
        log.info("[SCHEDULER] Registered interval task '{}' (every {})", name, interval);
    }

    @Override
    public boolean cancel(String name) {
        Task task = tasks.remove(name);
        if (task == null) return false;
        if (task.future != null) task.future.cancel(false);
        log.info("[SCHEDULER] Cancelled task '{}'", name);
        return true;
    }

    @Override
    public List<String> scheduled() {
        return new ArrayList<>(tasks.keySet());
    }

    @Override
    public Optional<Instant> nextRun(String name) {
        Task task = tasks.get(name);
        return task == null ? Optional.empty() : Optional.ofNullable(task.nextRun);
    }

    /** Stops the timer; in-flight task runs are not interrupted. */
    public void shutdown() {
        timer.shutdownNow();
        tasks.clear();
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private void scheduleNextCron(Task task) {
        if (!tasks.containsKey(task.name)) return; // cancelled
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = CronEvaluator.nextFireTime(task.cron, now);
        if (next == null) {
            log.warn("[SCHEDULER] Cron '{}' for task '{}' has no upcoming fire time — not rescheduled",
                    task.cron, task.name);
            return;
        }
        long delayMs = Math.max(0, Duration.between(now, next).toMillis());
        task.nextRun = next.atZone(ZoneId.systemDefault()).toInstant();
        task.future = timer.schedule(() -> {
            fire(task);
            scheduleNextCron(task); // re-arm after each cron fire
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void fire(Task task) {
        if (!task.running.compareAndSet(false, true)) {
            log.debug("[SCHEDULER] Skipping overlapping run of '{}'", task.name);
            return;
        }
        Thread.ofVirtual().name("vatn-task-" + task.name).start(() -> {
            try {
                task.runnable.run();
            } catch (Throwable t) {
                log.error("[SCHEDULER] Task '{}' threw", task.name, t);
            } finally {
                task.running.set(false);
            }
        });
    }

    private static final class Task {
        final String name;
        final Runnable runnable;
        final String cron;            // null for interval tasks
        final Duration interval;      // null for cron tasks
        final AtomicBoolean running = new AtomicBoolean(false);
        volatile ScheduledFuture<?> future;
        volatile Instant nextRun;

        Task(String name, Runnable runnable, String cron, Duration interval) {
            this.name = name;
            this.runnable = runnable;
            this.cron = cron;
            this.interval = interval;
        }
    }
}
