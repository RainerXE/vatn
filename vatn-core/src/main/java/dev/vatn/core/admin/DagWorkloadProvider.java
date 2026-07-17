package dev.vatn.core.admin;

import dev.vatn.api.admin.VWorkload;
import dev.vatn.api.admin.VWorkloadProvider;
import dev.vatn.api.workflow.VDagEngine;
import dev.vatn.api.workflow.VDagRun;
import dev.vatn.api.workflow.VDagRunState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DagWorkloadProvider implements VWorkloadProvider {

    private final VDagEngine dagEngine;

    public DagWorkloadProvider(VDagEngine dagEngine) {
        this.dagEngine = dagEngine;
    }

    @Override
    public List<VWorkload> getActiveWorkloads() {
        return dagEngine.listActiveRuns().stream()
            .map(run -> new VWorkload(
                run.runId(),
                "DAG: " + run.dagId(),
                VWorkload.Type.DAG_TASK,
                mapStatus(run.state()),
                run.startDate() != null ? run.startDate() : Instant.now(),
                Map.of("runId", run.runId())
            ))
            .collect(Collectors.toList());
    }

    private VWorkload.Status mapStatus(VDagRunState runState) {
        return switch (runState) {
            case QUEUED, RUNNING -> VWorkload.Status.RUNNING;
            case SUCCESS, CANCELED -> VWorkload.Status.STOPPED;
            case FAILED -> VWorkload.Status.FAILED;
        };
    }
}
