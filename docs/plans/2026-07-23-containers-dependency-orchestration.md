# Phase 3: Container Dependency Orchestration

## Goal
Add multi-container stack orchestration with dependency ordering, health probes (exec/TCP/HTTP), and startup sequencing.

## Design

### Records (vatn-api compatible, zero deps)

**HealthProbe** — how to check if a container is healthy:
```
type: String        "exec" | "tcp" | "http"
value: String       exec command ("curl localhost:8080/health"),
                    TCP target ("localhost:5432"),
                    or HTTP URL ("http://localhost:8080/health")
intervalMs: int     default 5000
timeoutMs: int      default 3000
retries: int        default 3
startPeriodMs: int  default 5000 (grace period)
```

**ServiceDependency** — one service depends on another:
```
service: String     service name within the stack
condition: String   "healthy" | "started" | "completed"
```

**StackService** — a container within a stack:
```
name: String        service name (e.g. "db", "api")
templateId: String  reference to ContainerTemplate
dependsOn: List<ServiceDependency>
healthProbe: HealthProbe (optional)
env: Map<String, String> (overrides)
```

**ContainerStack** — deployable group:
```
id: String
name: String
description: String
services: List<StackService>
createdAt: long
```

### StackDeployer

Orchestrates deployment of a ContainerStack:
1. **Validate DAG** — detect cycles, missing template refs
2. **Topological sort** — order services by depends_on
3. **Per-service deployment** — for each service in order:
   - Look up `ContainerTemplate` + `ResourceProfile` (if set)
   - Create container via `ContainerCreator`
   - Start container via `ContainerManager`
   - Wait for dependency conditions (if any)
   - Run health probe (if configured)
   - Record result
4. Returns `DeploymentRun` with results per service and overall status

**Integration:**
- `ContainerCreator` reused for container creation
- `VProcessService` for exec probes
- `VHttpClient` (vatn-api) for HTTP probes
- TCP probes via `java.net.Socket`
- Container start via existing managers (`GenericContainerManager` etc.)

### Files
- `HealthProbe.java` (new)
- `ServiceDependency.java` (new)
- `StackService.java` (new)
- `ContainerStack.java` (new)
- `StackService.java` (new)
- `StackDeployer.java` (new)
- `DeploymentRun.java` (new) — result record
- `ContainerStackStore.java` (new) — persistence
- `ContainerStackService.java` (new) — SPI
- Modify: `ContainersPlugin.java` — stack CRUD + deploy endpoints
- Modify: `ContainersHtml.java` — Stacks tab
- Modify: `ContainersPluginTest.java` — tests
- Modify: `reflect-config.json` — entries
