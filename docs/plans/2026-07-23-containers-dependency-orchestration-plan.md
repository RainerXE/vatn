# Container Dependency Orchestration — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add multi-container stack orchestration with dependency ordering, health probes (exec/TCP/HTTP), and startup sequencing.

**Architecture:** New records (HealthProbe, ServiceDependency, StackService, ContainerStack, DeploymentRun) + StackDeployer (DAG resolution + sequential deploy with health checks) + ContainerStackStore (persistence). ContainerCreator reused for container creation.

**Tech Stack:** Java 25, vatn-api VHttpClient, VProcessService, java.net.Socket

---

### Task 1: HealthProbe record + ServiceDependency record

**Files:**
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/HealthProbe.java`
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ServiceDependency.java`

**HealthProbe.java:**
```java
package dev.vatn.plugins.containers;

public record HealthProbe(
    String type,
    String value,
    int intervalMs,
    int timeoutMs,
    int retries,
    int startPeriodMs
) {
    public HealthProbe {
        if (type == null) type = "exec";
        if (intervalMs <= 0) intervalMs = 5000;
        if (timeoutMs <= 0) timeoutMs = 3000;
        if (retries <= 0) retries = 3;
        if (startPeriodMs <= 0) startPeriodMs = 5000;
    }
}
```

**ServiceDependency.java:**
```java
package dev.vatn.plugins.containers;

public record ServiceDependency(
    String service,
    String condition
) {
    public ServiceDependency {
        if (condition == null) condition = "healthy";
    }
}
```

---

### Task 2: StackService + ContainerStack records

**Files:**
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/StackService.java`
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainerStack.java`

**StackService.java:**
```java
package dev.vatn.plugins.containers;

import java.util.List;
import java.util.Map;

public record StackService(
    String name,
    String templateId,
    List<ServiceDependency> dependsOn,
    HealthProbe healthProbe,
    Map<String, String> env
) {
    public StackService {
        if (dependsOn == null) dependsOn = List.of();
        if (env == null) env = Map.of();
    }
}
```

**ContainerStack.java:**
```java
package dev.vatn.plugins.containers;

import java.util.List;

public record ContainerStack(
    String id,
    String name,
    String description,
    List<StackService> services,
    long createdAt
) {
    public ContainerStack {
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (services == null) services = List.of();
    }
}
```

---

### Task 3: ContainerStackService SPI + ContainerStackStore

**Files:**
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainerStackService.java`
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainerStackStore.java`

**ContainerStackService.java (SPI):**
```java
package dev.vatn.plugins.containers;

import java.util.List;
import java.util.Optional;

public interface ContainerStackService {
    List<ContainerStack> list();
    Optional<ContainerStack> get(String id);
    ContainerStack save(ContainerStack stack);
    void delete(String id);
}
```

**ContainerStackStore.java** — same pattern as JsonTemplateStore/JsonResourceProfileStore, persists to `<workspace>/stacks.json`.

---

### Task 4: DeploymentRun record + StackDeployer

**Files:**
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/DeploymentRun.java`
- Create: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/StackDeployer.java`

**DeploymentRun.java:**
```java
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
```

**StackDeployer.java** — orchestrates deployment:
- Constructor takes: `ContainerCreator`, `List<ContainerManager>`, `VProcessService`, `VHttpClient`
- `deploy(ContainerStack)` method:
  1. Build adjacency map from `depends_on`
  2. Detect cycles via DFS
  3. Topological sort
  4. For each service in sorted order:
     - Resolve template from TemplateService
     - Create container
     - Start container
     - Wait for dependencies' health (check previous ServiceResults)
     - Run health probe (if configured) with retries + timeout
  5. Return DeploymentRun

---

### Task 5: Wire stack endpoints into ContainersPlugin

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainersPlugin.java`

Add to `onInitialize`:
```java
ContainerStackService stackService = new ContainerStackStore(context.getWorkspacePath());
```

Add endpoints:
- `GET /api/stacks` — list stacks
- `GET /api/stacks/{id}` — get stack
- `POST /api/stacks` — save stack
- `DELETE /api/stacks/{id}` — delete stack
- `POST /api/stacks/{id}/deploy` — deploy stack (returns DeploymentRun)
- `POST /api/stacks/{id}/undeploy` — stop all stack containers

---

### Task 6: Update reflect-config.json

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/resources/META-INF/native-image/dev.vatn.plugins.containers/reflect-config.json`

Add entries for: `HealthProbe`, `ServiceDependency`, `StackService`, `ContainerStack`, `DeploymentRun`, `DeploymentRun$ServiceResult`, `StackDeployer`, `ContainerStackStore`.

---

### Task 7: Dashboard UI — Stacks tab

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/main/java/dev/vatn/plugins/containers/ContainersHtml.java`

Add:
- "Stacks" nav item 
- Stacks tab with list, detail view, deploy/undeploy buttons
- Stack detail showing service DAG, per-service status during deployment
- DeploymentRun result display (which services succeeded/failed)

---

### Task 8: Write tests

**Files:**
- Modify: `plugins/vatn-plugin-containers/src/test/java/dev/vatn/plugins/containers/ContainersPluginTest.java`

Tests to add:
1. `healthProbeDefaults()` — verifies default values
2. `serviceDependencyDefaults()` — verifies default condition
3. `stackServiceDefaults()` — verifies empty lists defaults
4. `containerStackDefaults()` — verifies UUID generation
5. `stackStoreSaveAndGet()` — CRUD test
6. `stackDeployerTopologicalSort()` — verify correct ordering with depends_on chain
7. `stackDeployerCycleDetection()` — verify cycle throws/returns error
8. `stackDeployerHealthProbeExec()` — verify exec probe runs and passes
9. `stackDeployerHealthProbeTcp()` — mock TCP probe
10. `stackDeployerHealthProbeHttp()` — mock HTTP probe via VHttpClient
