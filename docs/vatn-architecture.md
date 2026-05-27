# VATN Architecture

> An inside look at how the platform is structured, why it is built the way it is, and what each layer owns.

---

## Design Principles

**1. Zero-dependency SPI.**  
`vatn-api` has no external dependencies. Any language or framework can implement the interfaces. Your plugin compiles against only `vatn-api`; the runtime is injected at load time.

**2. One entry point.**  
Everything starts from `VNodeRunner.create(port).addPlugin(...).start()`. There is no XML, no annotation scanning, no classpath magic. Services are registered programmatically and resolved through `VNodeContext`.

**3. Virtual-thread native.**  
Every handler, subscriber, pipeline, and DAG task runs on a Java 25 virtual thread. Blocking I/O is the default programming model — no callbacks, no reactive chains, no manual executor management.

**4. SQLite-first persistence.**  
The runtime uses an embedded SQLite database for all internal state: DAG runs, task instances, the event log, job queue, advisory locks, and the logical clock. No external process is required. PostgreSQL or any JDBC-compatible database can be substituted via `VPersistenceService`.

**5. Security is structural, not optional.**  
Every plugin is assigned a `VTrustLevel` at load time based on its JAR signature. Every flow request is checked against that level. Policies are enforced before data moves, not after it arrives.

---

## Module Dependency Graph

```
vatn-bom
  └── version management only; no code

vatn-api
  └── zero external dependencies
      └── dev.vatn.api.*  (all SPI interfaces and records)
          └── workflow.*  (VDag, VDagEngine, VOperator, …)
          └── security.*  (VFirewall, VTrustLevel, VFlowPolicy, …)
          └── memory.*    (VMemoryEvent)

vatn-core
  ├── depends on: vatn-api
  ├── depends on: Helidon 4 SE (HTTP + WebSocket + SSE)
  ├── depends on: PF4J (plugin loader)
  ├── depends on: Jackson (JSON)
  ├── depends on: sqlite-jdbc (persistence)
  ├── depends on: SLF4J + Logback (logging)
  └── depends on: GraalVM SDK (native image — compile-time only)

vatn-cli
  ├── depends on: vatn-api
  ├── depends on: vatn-core (runtime, shaded into fat JAR)
  └── depends on: Picocli (CLI framework)

vatn-bench
  ├── depends on: vatn-api
  ├── depends on: vatn-core
  └── depends on: JMH (benchmarking framework)

vatn-test / vatn-verify
  ├── depends on: vatn-api
  └── depends on: vatn-core

vatn-plugin-indexer / vatn-plugin-scraper
  ├── depends on: vatn-api
  └── depends on: PF4J (plugin descriptor annotations)

examples/*
  ├── depends on: vatn-api
  └── depends on: vatn-core (shaded into fat JAR)
```

The critical constraint: **plugins must only depend on `vatn-api`**. `vatn-core` is provided by the node at runtime and must never appear as a compile dependency in plugin code. Violating this couples the plugin to implementation internals and breaks hot-swap and native-image compatibility.

---

## vatn-core Internals

### VNodeRunner — The Bootstrap

`VNodeRunner` is the single orchestration class. Its `start()` method:

1. Creates `VNodeContextImpl` (the service registry)
2. Creates and starts `VRegistry` (PF4J plugin loader + JAR verifier)
3. Initializes platform services: `VMessaging`, `VStream`, `VJson`, `VConfiguration`
4. Wires the DAG stack: `VDagRegistryImpl` → `VSubscriptionImpl` → `VDagEngineImpl` → `VDagSchedulerImpl`
5. Registers `VTracingService` (OTLP if `VATN_OTLP_ENDPOINT` is set; noop otherwise)
6. Calls `plugin.onInitialize(context)` for each registered plugin (wrapped in `ScopedValue.where(CURRENT_PLUGIN_ID, ...)`)
7. Mounts all HTTP registrations collected from plugins onto the Helidon router
8. Mounts built-in endpoints: `/vatn/health`, `/vatn/ready`, `/vatn/stream/{id}` (stream ingestion), `/vatn/ui/stream` (SSE bridge)
9. Starts the Helidon `WebServer`
10. Starts `VUdpDiscovery` (UDP multicast LAN announcements)

### VNodeContextImpl — The Service Registry

A `ConcurrentHashMap<Class<?>, Object>` behind a typed facade. Plugins register services via `registerService(Type.class, impl)` and resolve them via `getService(Type.class)`. The last registration wins — a plugin loaded later can replace a service registered by an earlier plugin or by the platform.

The second constructor (`VNodeContextImpl(nodeId, firewall, initialServices)`) accepts a pre-built map — used by `vatn-test` to inject mock services without starting a real node.

### OipcMessagingTransport — The IPC Layer

Implements `VMessaging` over the OIPC v2.12 binary protocol:

- Tries to bind a Unix Domain Socket first (`/tmp/vatn/vatn-<uuid>.sock`)
- Falls back to TCP loopback on any port if UDS is unsupported (Windows)
- An accept loop runs on a named virtual thread (`oipc-accept-loop`)
- Each incoming connection gets its own virtual-thread reader
- The 18-byte V3 binary header is parsed: magic `0x4F`, version, opcode, flags, 4-byte length, 8-byte correlation ID
- HELLO handshake verifies `Major = 2` before any message is processed
- In-process `subscribe`/`publish` (within the same JVM) bypasses the socket entirely for zero-copy delivery

Full wire format: [oipc-protocol.md](oipc-protocol.md).

### VDagEngineImpl — The Workflow Engine

Executes task graphs using the following algorithm:

1. `trigger(dagId, conf)` — creates a `VDagRun` (QUEUED), persists all task instances (NONE state), appends `DAG_TRIGGERED` to the event log, then calls `executeRun` on a virtual thread.
2. `executeRun` — iterates topology until all tasks are terminal:
   - For each NONE task whose upstreams are all SUCCESS: dispatch on a virtual thread
   - For each NONE task with a FAILED upstream: mark UPSTREAM_FAILED immediately
   - Sleep 50 ms between sweeps (no busy-wait)
3. Each task execution: calls `operator.execute(taskContext)`, persists state transitions (RUNNING → SUCCESS/FAILED), appends to the event log, retries on failure up to `retryPolicy.maxRetries` with exponential backoff.
4. `resumeInterruptedRuns()` — queries the event log for runs that were `DAG_TRIGGERED` but never reached `DAG_SUCCESS` / `DAG_FAILED`, rebuilds in-memory state from persisted task instances, and re-enters `executeRun` for each. Tasks with a `TASK_SUCCESS` event are skipped.

State machine per task:

```
NONE → RUNNING → SUCCESS
             └─> FAILED → (retry) → RUNNING
                        └─> (max retries) → FAILED
NONE → UPSTREAM_FAILED  (shortcircuited when an upstream is FAILED)
SUCCESS / FAILED / UPSTREAM_FAILED → REMOVED  (via cancel)
NONE → SKIPPED  (replay: already succeeded in a prior run)
```

### VRegistry — Plugin Loader and Trust Enforcer

Wraps PF4J's `DefaultPluginManager`. On `start()`:

1. `pluginManager.loadPlugins()` — discovers JARs in the plugin directory
2. `validatePlugins()` — for each plugin, reads `META-INF/vatn.sig` and `META-INF/vatn.key`, verifies the JAR bytes against the Ed25519 signature via `VPackageVerifier`
3. Assigns `VTrustLevel.RESTRICTED` to verified plugins, `VTrustLevel.SANDBOXED` to unsigned ones, `VTrustLevel.NONE` (refused) to invalid signatures
4. `pluginManager.startPlugins()` — activates only non-NONE plugins

Plugins added programmatically via `registerPlugin(plugin)` receive `VTrustLevel.FULL`.

### VNativeBridge — The C ABI

Provides `@CEntryPoint` methods for embedding a VATN node in a C or Rust process:

| C function | Description |
|------------|-------------|
| `vatn_node_start(config_json)` | Boot a node; returns an opaque long handle |
| `vatn_node_stop(handle)` | Shut down a node by handle |
| `vatn_call(handle, service, method, payload, size, result)` | Dispatch an OIPC call |
| `vatn_free_buffer(buffer)` | Release a result buffer |
| `vatn_get_diagnostics(handle, result)` | JSON status of the node |
| `vatn_set_debug(enabled)` | Toggle debug-level logging |
| `vatn_get_last_error()` | Retrieve the last captured error string |

Handles map to `VNodeRunner` instances in a `ConcurrentHashMap<Long, VNodeRunner>`. The `doStartNode(String)` helper (package-private) separates Java logic from the C ABI surface, making it callable from tests without GraalVM types on the classpath.

---

## Security Architecture

```
Plugin JAR
    │
    ▼
VRegistry.validatePlugins()
    │
    ├─ No sig bundle → SANDBOXED
    ├─ Sig invalid   → NONE (refused)
    └─ Sig valid     → RESTRICTED

    At every VStream.createPolicyStream():
    │
    ├─ Check VTrustLevel (plugin trust vs policy.requiredTrust)
    │      fail → SecurityException
    │
    └─ Consult VPolicyInterjectors (in order)
           DENY → publish PolicyViolation to vatn.monitor.security → SecurityException
           ALLOW → proceed
           PASS  → next interjector
           (no ALLOW after all interjectors) → SecurityException
```

`VatnSecurity.CURRENT_PLUGIN_ID` is a `ScopedValue` — it is set by `VNodeRunner` before calling any plugin method and propagates automatically through virtual-thread forks within that scope. Any service boundary can read it without the plugin passing an identity token explicitly.

---

## Data Flow: HTTP Request → Plugin Handler

```
Client
  │  TCP
  ▼
Helidon WebServer (virtual thread per request)
  │
  ▼
VNodeRunner routing table
  │  matches /hello/*
  ▼
VHttpServiceAdapter.handle(ServerRequest, ServerResponse)
  │  wraps in VHttpRequest / VHttpResponse
  ▼
ScopedValue.where(CURRENT_PLUGIN_ID, "com.example.hello")
  └─ HelloPlugin.handler(req, res)
       │  blocking Java code, arbitrary I/O
       ▼
      res.send("Hello!")
       │
       ▼
VHttpResponseAdapter → Helidon ServerResponse.send()
       │
       ▼
Client ← TCP response
```

No thread switches. The Helidon virtual thread that accepted the connection runs the entire handler to completion.

---

## Data Flow: Plugin → Pub/Sub

```
Plugin A publishes to "orders.created":
  messaging.publish("orders.created", bytes)
      │
      ▼  (OipcMessagingTransport.publish)
  In-process subscribers? → Thread.ofVirtual().start(subscriber) for each
  OIPC socket subscribers? → write PUSH frame to each connected channel
      │
      ▼
Plugin B subscriber callback (on its own virtual thread):
  bytes → process order
```

In v1, all subscribers are in-process. In v2, OIPC bridges extend the same `VMessaging` API across nodes transparently.

---

## SQLite Schema

VATN creates these tables in the workspace database (`vatn.db`):

| Table | Owner | Purpose |
|-------|-------|---------|
| `vatn_dag_runs` | VDagEngineImpl | One row per DAG run; state, start/end times |
| `vatn_task_instances` | VDagEngineImpl | One row per task per run; state, attempt count, result |
| `vatn_xcom` | VDagEngineImpl | Key-value pairs scoped to a run; XCom push/pull |
| `vatn_event_log` | VEventLogImpl | Append-only log of every task state transition |
| `vatn_job_queue` | VJobQueueImpl | Background job records; retry, TTL, claim |
| `vatn_clock` | VClockServiceImpl | Logical clock persistence across restarts |
| `vatn_locks` | VResourceLockServiceImpl | Advisory lock state |

All tables are created via `CREATE TABLE IF NOT EXISTS` — safe to run on every startup.

---

## Adding a New Platform Service

1. **Define the interface in `vatn-api`**: extend `VService`, annotate with `@VatnApi(since = "X.X")`.
2. **Implement in `vatn-core`**: create `VMyServiceImpl implements VMyService`.
3. **Register in `VNodeRunner.start()`**: `context.registerService(VMyService.class, new VMyServiceImpl(...))`.
4. **Expose via `VNodeContext`** (optional): add a typed accessor `getMyService()` to `VNodeContext` and implement it in `VNodeContextImpl`.
5. **Write tests** in `vatn-core/src/test`.
6. **Document** in the Service SPI table in [dev-guide.md](dev-guide.md).

---

*For the OIPC wire protocol, see [oipc-protocol.md](oipc-protocol.md). For getting started building on VATN, see [dev-guide.md](dev-guide.md).*
