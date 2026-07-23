package dev.vatn.plugins.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A supervised Python process — wraps a daemon step from a Pinokio script.
 *
 * <p>Starts the process, captures stdout/stderr to a circular log buffer,
 * and auto-restarts on crash when {@code autoRestart} is true.
 */
public class PythonProcess {

    private static final Logger log = LoggerFactory.getLogger(PythonProcess.class);

    public enum Status { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private final String             id;
    private final String             appId;
    private final List<String>       command;
    private final Map<String, String>env;
    private final Path               workDir;
    private final boolean            autoRestart;
    private final int                restartDelayMs;
    private final int                maxLogLines;

    private final Deque<String>      logBuffer;
    private final AtomicReference<Status>  status = new AtomicReference<>(Status.STOPPED);
    private final AtomicInteger      restartCount = new AtomicInteger(0);
    private volatile long            pid          = -1;
    private volatile int             lastExitCode = 0;
    private volatile Instant         startedAt;
    private volatile boolean         stopped      = false;
    private volatile Process         currentProcess;

    public PythonProcess(String id, String appId, List<String> command,
                         Map<String, String> env, Path workDir,
                         boolean autoRestart, int restartDelayMs, int maxLogLines) {
        this.id             = id;
        this.appId          = appId;
        this.command        = command;
        this.env            = env;
        this.workDir        = workDir;
        this.autoRestart    = autoRestart;
        this.restartDelayMs = restartDelayMs;
        this.maxLogLines    = maxLogLines;
        this.logBuffer      = new ArrayDeque<>(maxLogLines + 1);
    }

    /** Starts the supervisor loop in a virtual thread. */
    public void start() {
        if (status.get() == Status.RUNNING || status.get() == Status.STARTING) return;
        stopped = false;
        status.set(Status.STARTING);
        Thread.ofVirtual().name("python-proc-" + id).start(this::supervisorLoop);
    }

    /** Stops the process gracefully. */
    public void stop() {
        stopped = true;
        status.set(Status.STOPPING);
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(3000);
                    if (p.isAlive()) p.destroyForcibly();
                } catch (InterruptedException ignored) {}
            });
        }
    }

    private void supervisorLoop() {
        do {
            try {
                startedAt = Instant.now();
                ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectErrorStream(true);
                if (workDir != null) pb.directory(workDir.toFile());
                pb.environment().clear();
                pb.environment().putAll(env);

                currentProcess = pb.start();
                pid = currentProcess.pid();
                status.set(Status.RUNNING);
                addLog("[VATN] Process started (pid=" + pid + ")");
                log.info("[PYTHON:{}] Started pid={}", id, pid);

                // Capture stdout+stderr in a virtual thread
                Thread.ofVirtual().name("python-log-" + id).start(() -> {
                    try (var reader = new BufferedReader(
                            new InputStreamReader(currentProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) addLog(line);
                    } catch (Exception ignored) {}
                });

                lastExitCode = currentProcess.waitFor();
                pid = -1;
                addLog("[VATN] Process exited (code=" + lastExitCode + ")");
                log.info("[PYTHON:{}] Exited code={}", id, lastExitCode);

                if (!stopped && autoRestart) {
                    restartCount.incrementAndGet();
                    status.set(Status.CRASHED);
                    addLog("[VATN] Auto-restarting in " + restartDelayMs + " ms (restart #" + restartCount.get() + ")");
                    Thread.sleep(restartDelayMs);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                addLog("[VATN] Error: " + e.getMessage());
                log.error("[PYTHON:{}] Supervisor error: {}", id, e.getMessage());
                if (!stopped && autoRestart) {
                    try { Thread.sleep(restartDelayMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        } while (!stopped && autoRestart);

        status.set(Status.STOPPED);
        log.info("[PYTHON:{}] Supervisor loop ended", id);
    }

    private synchronized void addLog(String line) {
        logBuffer.addLast(line);
        while (logBuffer.size() > maxLogLines) logBuffer.pollFirst();
    }

    public synchronized List<String> getLogTail(int lines) {
        var tail = logBuffer.stream().toList();
        int from = Math.max(0, tail.size() - lines);
        return tail.subList(from, tail.size());
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String   id()           { return id; }
    public String   appId()        { return appId; }
    public Status   status()       { return status.get(); }
    public long     pid()          { return pid; }
    public int      restartCount() { return restartCount.get(); }
    public int      lastExitCode() { return lastExitCode; }
    public Instant  startedAt()    { return startedAt; }
    public boolean  autoRestart()  { return autoRestart; }
}
