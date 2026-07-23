package dev.vatn.plugins.containers;

import java.util.List;

public record DeploymentRun(
    String stackId,
    String overallStatus,
    List<ServiceResult> results
) {
    public record ServiceResult(
        String serviceName,
        String status,
        String containerId,
        String error
    ) {}
}
