# VATN Examples

**VATN** (Virtual Application Transaction Node) is a JVM runtime that combines the developer ergonomics of Node.js with the concurrency and performance of the JVM. It runs on virtual threads (Project Loom), making it trivially scalable without reactive ceremony.

---

## What is VATN?

| Concern | VATN approach |
|---------|--------------|
| HTTP / WebSocket | Built on [Helidon 4 SE](https://helidon.io/) — non-blocking, virtual-thread backed |
| Plugin system | `VNodePlugin` SPI — drop a JAR in `/plugins`, it loads and wires itself |
| Workflow / DAG | Airflow-inspired `VDagEngine` — define task graphs in code, run them locally |
| Pub/Sub messaging | `VMessaging` — in-process (same JVM) or bridged (OIPC sockets, Release 2) |
| Persistence | `VPersistenceService` — JDBC (SQLite by default, swap to Postgres) |
| Observability | Structured logging + OTLP tracing (`VATN_OTLP_ENDPOINT` env var) |
| Secrets | `VSecretService` — env-backed, vault-ready SPI |
| Security | `VGuardService` — guard policies per plugin/endpoint |

### Core concept: plugins

Everything in VATN is a plugin. Your app extends `VNodePlugin`, registers its HTTP services and workflow operators inside `onInitialize`, and VATN wires the rest:

```java
public class MyPlugin implements VNodePlugin {
    public String getId()      { return "com.example.my-plugin"; }
    public String getName()    { return "My Plugin"; }
    public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.register("/api", new MyHttpService());
    }
}
```

Bootstrap in one line:

```java
VNodeRunner.create(8080).addPlugin(new MyPlugin()).start();
```

---

## Getting started

**Prerequisites**: Java 21+, Maven 3.9+

Add the VATN API dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>dev.vatn</groupId>
    <artifactId>vatn-api</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- runtime (include when running standalone) -->
<dependency>
    <groupId>dev.vatn</groupId>
    <artifactId>vatn-core</artifactId>
    <version>1.0.0</version>
    <scope>runtime</scope>
</dependency>
```

---

## Examples

| # | Example | What you learn |
|---|---------|----------------|
| [01](01-hello-world/) | Hello World | Minimal plugin, one HTTP endpoint |
| [02](02-rest-api/) | REST API (Task Manager) | CRUD, path params, JSON, persistence |
| [03](03-websocket-chat/) | WebSocket Chat | Real-time messaging, session broadcast |
| [04](04-dag-etl-pipeline/) | DAG ETL Pipeline | Workflow engine, operators, XCom, retry |
| [05](05-realtime-dashboard/) | Real-time Dashboard | SSE, pub/sub, live metric streaming |
| [06](06-scheduled-report/) | Scheduled Report | Cron DAG, sensors, scheduled workflows |
| [07](07-chat-app/) | Chat App | Full-stack: HTML UI + WebSocket, username login, rooms, typing indicators, history |

### Legacy / advanced examples

| Folder | Description |
|--------|-------------|
| [chaos-suite/](chaos-suite/) | C plugins that exercise the OIPC watchdog (process restart, memory limits) |
| [polyglot-ffi/](polyglot-ffi/) | Java + C + Odin + Python FFI via Panama (advanced) |
| [python-plugin/](python-plugin/) | Raw Python OIPC socket plugin |

> These use lower-level OIPC directly rather than the `VNodePlugin` Java SPI.

---

## VATN vs. Node.js analogy

| Node.js concept | VATN equivalent |
|-----------------|----------------|
| `express.Router` | `VHttpService` + `VHttpRoutes` |
| `socket.io` | `VWsListener` + `VWsSession` |
| `EventEmitter` | `VMessaging` (pub/sub) |
| `Bull` / job queues | `VDagEngine` + `VOperator` |
| `node-cron` | `VDag.scheduled()` + `VDagScheduler` |
| `.env` / dotenv | `VConfiguration` + `VSecretService` |
| `stream.pipe()` | `VStream` |
| Plugin / middleware | `VNodePlugin` |

---

## Release 2 — Federation (VATN Lattice)

Release 1 (current) ships with basic LAN federation: `VDiscovery` and `VNameResolver` are live
on every node, and nodes on the same network find each other via UDP multicast. This is enough for
local multi-node development and demos, but it is **not production-grade federation** — there is no
TLS, no cryptographic bonding, no workload delegation, and no cross-datacenter routing.

**Release 2 upgrades basic LAN discovery to a full VATN Lattice** — production-ready federation
with cryptographically verified node identities, encrypted cross-node channels, and distributed
workload execution:

### What changes

- **`VDiscovery`** is upgraded: Release 1 broadcasts via UDP on the local LAN; Release 2 adds
  bonded node registration, cryptographic peer verification (`VBondStore`), and cross-datacenter routing.
- **`VMessaging` goes distributed**: topics can span nodes via OIPC 2.x bridges. A plugin on Node A publishes to `agent.output`; a subscriber on Node B receives it transparently.
- **`VDagEngine` becomes federated**: tasks can specify a `nodeAffinity` constraint. The engine routes task execution to the node that matches (e.g., GPU-equipped nodes for inference tasks).
- **`VRemotePluginProxy`**: call a plugin on a remote node as if it were local. The SPI is already in `vatn-api`; the transport layer lands in Release 2.
- **OIPC 2.12 mesh**: the existing OIPC socket protocol is extended with a cluster-membership gossip layer. Nodes form a mesh; the Supervisor on each node monitors peer health and reroutes traffic on failure.
- **Lattice CLI**: `vatn lattice join <seed-address>` enrolls a node into a cluster.

### What stays the same

Your plugin code does not change. `VNodeContext` is the same interface. If `VDiscovery` finds a peer, `VMessaging` routes to it — your `subscribe` call is identical whether the publisher is local or remote.

### Why this matters

Federation enables multi-node architectures where a "planner" node decomposes work and "executor" nodes run DAG tasks in parallel across machines — a pattern used by AI agent hosts and distributed data pipelines alike.

---

## OIPC Protocol (v2.12)

VATN plugins communicate over **OIPC (Octet IPC)** — a lightweight, binary/JSON message protocol over Unix Domain Sockets or TCP. See [`docs/oipc-protocol.md`](../docs/oipc-protocol.md) for the full spec.

The Java `VNodePlugin` SPI abstracts OIPC entirely. You only need OIPC directly if you write plugins in other languages (Python, Rust, C — see the legacy examples).

---

## Performance

Typical numbers on a MacBook M3 (wrk, 8 threads, 256 connections, 30s):

| Scenario | Req/s |
|----------|-------|
| VATN `/ping` (plain text) | ~310,000 |
| VATN `/json` (small JSON) | ~280,000 |
| Spring Boot 3 `/ping` | ~243,000 |
| Node.js/Express `/ping` | ~78,000 |

DAG engine latency (JMH, warm JVM):

| Scenario | Avg latency |
|----------|-------------|
| Single-task DAG trigger | ~0.8 ms |
| 10-task serial chain | ~9 ms |
| 10-task fan-out | ~2 ms |
| Windmill (Python, 40 tasks) | ~2,400 ms |
| Airflow (40 tasks) | ~56,000 ms |

---
