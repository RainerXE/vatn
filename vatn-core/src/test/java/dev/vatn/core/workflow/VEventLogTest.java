package dev.vatn.core.workflow;

import dev.vatn.api.workflow.VEventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VEventLogTest {

    @TempDir Path tempDir;
    private VDagEngineTestHarness harness;
    private VEventLog log;

    @BeforeEach
    void setUp() throws Exception {
        harness = new VDagEngineTestHarness(tempDir);
        log = new VEventLogImpl(harness.db());
    }

    @AfterEach
    void tearDown() { }

    @Test
    void appendAndQueryTaskSuccess() {
        log.append("run-1", "dag-1", "task-a", "TASK_SUCCESS", "output");
        assertTrue(log.hasSucceeded("run-1", "task-a"));
        assertFalse(log.hasSucceeded("run-1", "task-b"));
        assertFalse(log.hasSucceeded("run-2", "task-a"));
    }

    @Test
    void getInterruptedRunIds_returnsRunsWithoutCompletion() {
        log.append("run-interrupted", "dag-1", null, "DAG_TRIGGERED", null);
        log.append("run-done", "dag-1", null, "DAG_TRIGGERED", null);
        log.append("run-done", "dag-1", null, "DAG_SUCCESS", null);

        List<String> interrupted = log.getInterruptedRunIds();
        assertTrue(interrupted.contains("run-interrupted"));
        assertFalse(interrupted.contains("run-done"));
    }

    @Test
    void getInterruptedRunIds_emptyWhenAllComplete() {
        log.append("run-1", "dag-1", null, "DAG_TRIGGERED", null);
        log.append("run-1", "dag-1", null, "DAG_FAILED", null);
        assertTrue(log.getInterruptedRunIds().isEmpty());
    }

    @Test
    void hasSucceeded_falseWhenOnlyStarted() {
        log.append("run-1", "dag-1", "task-a", "TASK_STARTED", null);
        assertFalse(log.hasSucceeded("run-1", "task-a"));
    }

    @Test
    void multipleEventsForSameTask() {
        log.append("run-1", "dag-1", "task-a", "TASK_STARTED", null);
        log.append("run-1", "dag-1", "task-a", "TASK_FAILED", "error 1");
        log.append("run-1", "dag-1", "task-a", "TASK_STARTED", null);
        log.append("run-1", "dag-1", "task-a", "TASK_SUCCESS", "ok");
        assertTrue(log.hasSucceeded("run-1", "task-a"));
    }
}
