package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;
import dev.vatn.api.VNodeContext;

import java.util.Map;

/**
 * Runtime context passed to a {@link VOperator} when it executes.
 *
 * <p>Provides access to run identity, configuration, XCom, and logging.
 * Operators must not retain a reference to this object after {@code execute()} returns.
 */
@VatnApi(since = "1.0")
public interface VTaskContext {

    /** The DAG run ID that this task instance belongs to. */
    String getRunId();

    /** The DAG ID. */
    String getDagId();

    /** The task ID being executed. */
    String getTaskId();

    /** 1-based attempt counter. 1 = first try, 2 = first retry, etc. */
    int getTryNumber();

    /**
     * Merged run configuration: {@link VDag#defaultArgs()} overridden by {@link VDagRun#conf()}.
     */
    Map<String, String> getConf();

    /** Task metadata from {@link VDagTask#metadata()}. */
    Map<String, Object> getMetadata();

    /** XCom interface for reading outputs of upstream tasks and writing this task's output. */
    VXCom getXCom();

    /** Node-level services (messaging, persistence, etc.). */
    VNodeContext getNodeContext();

    /** Emit a log line associated with this task instance. */
    void log(String message);

    /** Emit a formatted log line associated with this task instance. */
    default void log(String format, Object... args) {
        log(String.format(format, args));
    }
}
