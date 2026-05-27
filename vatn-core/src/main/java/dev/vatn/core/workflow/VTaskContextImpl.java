package dev.vatn.core.workflow;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Runtime context passed to an operator during task execution. */
class VTaskContextImpl implements VTaskContext {
    private static final Logger logger = LoggerFactory.getLogger(VTaskContextImpl.class);

    private final String runId;
    private final String dagId;
    private final String taskId;
    private final int tryNumber;
    private final Map<String, String> conf;
    private final Map<String, Object> metadata;
    private final VXCom xcom;
    private final VNodeContext nodeContext;

    VTaskContextImpl(String runId, String dagId, String taskId, int tryNumber,
                     Map<String, String> conf, Map<String, Object> metadata,
                     VXCom xcom, VNodeContext nodeContext) {
        this.runId = runId;
        this.dagId = dagId;
        this.taskId = taskId;
        this.tryNumber = tryNumber;
        this.conf = Map.copyOf(conf);
        this.metadata = Map.copyOf(metadata);
        this.xcom = xcom;
        this.nodeContext = nodeContext;
    }

    @Override public String getRunId() { return runId; }
    @Override public String getDagId() { return dagId; }
    @Override public String getTaskId() { return taskId; }
    @Override public int getTryNumber() { return tryNumber; }
    @Override public Map<String, String> getConf() { return conf; }
    @Override public Map<String, Object> getMetadata() { return metadata; }
    @Override public VXCom getXCom() { return xcom; }
    @Override public VNodeContext getNodeContext() { return nodeContext; }

    @Override
    public void log(String message) {
        logger.info("[TASK {}/{}] {}", dagId + "/" + runId, taskId, message);
    }
}
