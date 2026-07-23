# VATN — Runtime for Personal Services

**A high-performance, plugin-native JVM runtime for building and running personal services — HTTP APIs, workflow pipelines, container management, scheduled jobs, and more — on your own hardware.**

VATN gives you the developer ergonomics of Node.js — one-liner server start, drop-in plugins, built-in pub/sub — but runs on the JVM with Java 25 virtual threads, delivering throughput comparable to Go or Rust while shipping a full production stack out of the box: HTTP, WebSocket, DAG workflows, distributed tracing, secrets, cryptographic identity, and an integrated web admin interface.

```
┌─────────────────────────────────────────────────────────────────┐
│  Your Plugin          implements VNodePlugin                    │
│  onInitialize(ctx) → ctx.register("/api", new MyService());     │
│                                                                 │
│  VNodeRunner.create(8080).addPlugin(new MyPlugin()).start();    │
└─────────────────────────────────────────────────────────────────┘
```

---

## Why VATN?

| | Node.js / Express | Spring Boot | **VATN** |
|---|---|---|---|
| Startup time | ~300 ms | ~4–8 s | ~200 ms (JVM) / ~10 ms (native) |
| HTTP throughput (TechEmpower-style) | ~78 k req/s | ~243 k req/s | **~310 k req/s** |
| Concurrency model | Event loop (async/await) | Thread pool or reactive | **Virtual threads — blocking style, zero threads wasted** |
| Plugin system | NPM packages | Spring Beans / auto-config | **`VNodePlugin` SPI — JAR drop-in, hot-swap, trust-level enforcement** |
| DAG / workflow engine | Bull, Agenda (third-party) | Spring Batch (heavy) | **Built-in `VDagEngine` — Airflow-inspired, SQLite-backed, crash-safe** |
| Work queues | Bull/BullMQ + Redis | RabbitMQ / SQS clients | **`VQueueService` — named queues, claim/ack, DLQ, atomic enqueue; no broker** |
| Durable pub/sub | Kafka / Redis Streams | Spring Cloud Stream | **`VTopicService` — per-consumer offsets, replay, seek; SQLite-backed** |
| Advisory locks | Redlock (Redis) | `@Lock` + DB row | **`VResourceLockService` — TTL-protected, RAII `VLock`, crash-safe** |
| Rate limiting | express-rate-limit / Nginx | Spring `@RateLimiter` | **`VRateLimiter` — inbound routes + outbound upstream quotas; per-second or windowed** |
| Outbound HTTP | axios / node-fetch | RestTemplate / WebClient | **`VHttpClient` — resilient: retry/backoff, ETag/TTL cache, per-host circuit breaker** |
| Periodic scheduler | node-cron / Agenda | `@Scheduled` | **`VScheduler` — cron or fixed-interval, skip-on-overlap, virtual-thread dispatch** |
| Blob / content store | multer + S3 SDK | Spring Content | **`VBlobStore` — CAS (sha256), streaming, range reads, pin/evict; S3 backend via plugin** |
| Secrets | dotenv / Vault SDK | Spring Vault | **`VSecretService` — AES-256-GCM, filesystem-backed, vault-ready SPI** |
| Web admin interface | None | Spring Boot Admin | **`vatn-webadmin` — ZimaOS-style glassmorphic dashboard, runs as a background daemon** |
| Container management | Portainer (separate) | None | **`vatn-plugin-containers` — Docker/Podman/Distrobox GUI + xterm shell terminals** |
| GraalVM native image | No | Optional | **First-class — `vatn_node_start()` C ABI** |
| Language interop | N-API (C) | JNI | **OIPC v2.12 — language-agnostic binary protocol over UDS/TCP** |

---

## Architecture

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │                          vatn-api  (SPI)                             │
 │  VNodePlugin · VNodeContext · VHttpService · VMessaging · VDagEngine │
 │  VGuardService · VSecretService · VNodeIdentity · VDiscovery · ...   │
 └───────────────────────────┬──────────────────────────────────────────┘
                             │  implemented by
 ┌───────────────────────────▼──────────────────────────────────────────┐
 │                       vatn-core  (runtime)                           │
 │                                                                      │
 │   VNodeRunner ──── Helidon 4 SE HTTP ──── VHttpService adapters      │
 │        │                                                             │
 │        ├── VRegistry (PF4J plugin loader, Ed25519 verification)      │
 │        ├── VDagEngineImpl  (SQLite-backed, crash-safe replay)        │
 │        ├── OipcMessagingTransport  (UDS / TCP, OIPC v2.12)           │
 │        ├── VNodeIdentityImpl  (Ed25519 keypair, ~/.vatn/.identity)   │
 │        ├── VSecretServiceImpl  (AES-256-GCM, ~/.vatn/secrets/)       │
 │        ├── VUdpDiscovery  (UDP multicast LAN announcements)          │
 │        └── VNativeBridge  (@CEntryPoint C ABI for GraalVM)           │
 └──────────────┬───────────────────────────────────────────────────────┘
                │  loaded into
 ┌──────────────▼───────────────────────────────────────────────────────┐
 │             plugins/vatn-plugin-*  ·  vatn-webadmin                 │
 │   your own VNodePlugin impls  ·  official plugin ecosystem           │
 └──────────────────────────────────────────────────────────────────────┘

 ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────┐
 │  vatn-cli    │  │  vatn-bench  │  │  vatn-test   │  │ vatn-spec  │
 │  vatn run    │  │  JMH / wrk   │  │  harness     │  │ OIPC spec  │
 │  vatn init   │  │  benchmarks  │  │              │  │            │
 │  vatn test   │  └──────────────┘  └──────────────┘  └────────────┘
 └──────────────┘
```

---

## Repository Layout (Monorepo)

Everything lives in a single Maven reactor — one `mvn clean install -DskipTests` builds the entire stack.

```
vatn/
├── vatn-api/                   ← SPI interfaces & annotations (zero runtime deps)
├── vatn-bom/                   ← Bill of Materials
├── vatn-spec/                  ← Manifest & plugin-spec models
├── vatn-core/                  ← Helidon 4 SE node engine
├── vatn-cli/                   ← CLI entry point (picocli)
├── vatn-verify/                ← Integration & verification tests
├── vatn-test/                  ← Shared test harness
├── vatn-bench/                 ← JMH benchmarks + load-test scripts
├── vatn-webadmin/              ← Web Admin (bundled daemon — see below)
│
├── plugins/                    ← Official plugin suite (25 plugins)
│   ├── vatn-plugin-admin/
│   ├── vatn-plugin-auth/
│   ├── vatn-plugin-containers/
│   ├── vatn-plugin-devenv/
│   └── ... (20 more)
│
└── examples/                   ← 12 runnable examples
    ├── 01-hello-world/
    ├── 04-dag-etl-pipeline/
    ├── 12-task-queue/
    └── ...
```

---

## Installation

One command installs the VATN CLI, Web Admin daemon, and your chosen plugins:

```bash
curl -fsSL https://raw.githubusercontent.com/RainerXE/vatn/main/install.sh | bash
```

The installer presents a component selection menu — all four components are enabled by default:

```
  ━━  Component selection  ━━

  [1] VATN Core Runtime  — CLI, node engine, DAG, queues, scheduler
    Install VATN Core? [Y/n]:

  [2] VATN Web Admin  — browser-based admin & container GUI (background service)
      Installs as a launchd / systemd daemon, starts automatically
    Install VATN Web Admin? [Y/n]:

  [3] Plugins  — auth, CORS, Swagger, metrics, postgres, redis, WASM, and more
    Install Plugins? [Y/n]:

  [4] Examples  — runnable Maven projects showing core VATN concepts
    Clone Examples? [Y/n]:
```

### Non-interactive / CI install

```bash
VATN_INSTALL_DIR=~/.vatn \
VATN_JAVA=graal \
VATN_COMPONENTS=core,webadmin,plugins \
VATN_PLUGINS=cors,auth,swagger,postgres \
  bash <(curl -fsSL https://raw.githubusercontent.com/RainerXE/vatn/main/install.sh)
```

| Variable | Values | Default |
|----------|--------|---------|
| `VATN_INSTALL_DIR` | any path | `~/.vatn` |
| `VATN_JAVA` | `graal` / `graalce` / `skip` | interactive |
| `VATN_COMPONENTS` | `all` or comma-list of `core,webadmin,plugins,examples` | `all` |
| `VATN_PLUGINS` | comma-list, `recommended`, `all` | `recommended` |

### Installed layout

```
~/.vatn/
├── bin/
│   ├── vatn              ← VATN CLI (added to PATH)
│   └── vatn-webadmin     ← Web Admin daemon binary / launcher
├── lib/
│   ├── vatn-cli.jar
│   └── vatn-webadmin.jar
├── plugins/              ← drop plugin JARs here; auto-loaded at startup
├── config/
│   └── vatn.conf
└── logs/
    ├── webadmin.out.log
    └── webadmin.err.log
```

### After installation

```bash
source ~/.zshrc          # reload shell (or open a new terminal)

vatn --version           # VATN Runtime 1.0-alpha.14
vatn init my-project     # scaffold a new plugin project
cd my-project
vatn run                 # starts node on :8080
```

---

## VATN Web Admin

`vatn-webadmin` is the official browser-based administration interface for VATN. It bundles the core runtime with three plugins and runs as a background daemon — starting automatically on login.

### Features

| Section | What you get |
|---------|-------------|
| **Admin Dashboard** | JVM heap & thread stats, loaded plugins, DAG workload monitor, system health |
| **Containers** | List and manage Docker, Podman, and Distrobox containers with start/stop/shell access |
| **Web Terminal** | Full xterm.js terminal directly into any running container via WebSocket |

### Access

After installation:

```
Admin Dashboard  →  http://localhost:8080/vatn/admin
Containers GUI   →  http://localhost:8080/vatn/containers
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP port |
| `VATN_ADMIN_USER` | `admin` | Login username |
| `VATN_ADMIN_PASS` | `vatnadmin` | Login password — **change in production** |
| `VATN_JWT_SECRET` | dev placeholder | JWT signing secret — **must be 32+ chars in production** |

### Service management

**macOS** (launchd):
```bash
launchctl stop dev.vatn.webadmin       # stop
launchctl start dev.vatn.webadmin      # start
launchctl unload ~/Library/LaunchAgents/dev.vatn.webadmin.plist  # uninstall
```

**Linux** (systemd):
```bash
systemctl --user stop vatn-webadmin    # stop
systemctl --user start vatn-webadmin   # start
systemctl --user disable vatn-webadmin # uninstall from autostart
```

### Build & run locally

```bash
# from the monorepo root
mvn clean install -pl vatn-webadmin --also-make -DskipTests

# run directly
java -jar vatn-webadmin/target/vatn-webadmin-1.0-alpha.14.jar

# build native binary (requires GraalVM)
mvn clean package -Pnative -pl vatn-webadmin --also-make -DskipTests
./vatn-webadmin/target/vatn-webadmin
```

---

## Plugins

VATN's plugin system allows any capability to be dropped in as a JAR. All official plugins live in `plugins/` and compile alongside the runtime in the same reactor.

### Official Plugin Catalog

| Plugin | Description |
|--------|-------------|
| `vatn-plugin-admin` | Admin dashboard — heap stats, threads, DAG monitor, workload registry |
| `vatn-plugin-auth` | JWT + API-key bearer authentication |
| `vatn-plugin-containers` | Container GUI — Docker/Podman/Distrobox management + xterm.js web terminals |
| `vatn-plugin-cors` | CORS filter for browser-accessible APIs |
| `vatn-plugin-security` | CSRF protection, rate limiting, security headers |
| `vatn-plugin-swagger` | OpenAPI / Swagger UI at `/api/docs` |
| `vatn-plugin-bcrypt` | BCrypt password hashing service |
| `vatn-plugin-postgres` | PostgreSQL connection pool (HikariCP) |
| `vatn-plugin-redis` | Redis client (Jedis) |
| `vatn-plugin-mongodb` | MongoDB driver integration |
| `vatn-plugin-openai` | OpenAI / Claude / local LLM client |
| `vatn-plugin-metrics` | Prometheus `/metrics` endpoint (Micrometer) |
| `vatn-plugin-email` | SMTP email via Jakarta Mail |
| `vatn-plugin-slack` | Slack webhook + Events API |
| `vatn-plugin-s3` | AWS S3 / compatible object storage |
| `vatn-plugin-wasm` | WebAssembly execution via Chicory (no native deps) |
| `vatn-plugin-devenv` | Developer environment scanner — runtimes, LLMs, containers, editors |
| `vatn-plugin-fts` | Full-text search (SQLite FTS5, BM25 ranking, snippets) |
| `vatn-plugin-python` | Python runtime bridge |
| `vatn-plugin-node` | Node.js runtime bridge |
| `vatn-plugin-comm` | Communication hub — Telegram, Signal, RCS |
| `vatn-plugin-activitypub` | ActivityPub / Fediverse federation |
| `vatn-plugin-terminalphone` | Encrypted audio terminal over Tor |
| `vatn-plugin-mongodb` | MongoDB driver integration |

### Writing your own plugin

```java
public class MyPlugin implements VNodePlugin {

    @Override
    public void onInitialize(VNodeContext ctx) {
        // Register an HTTP service
        ctx.route("GET", "/hello", (req, res) ->
            res.send("Hello from MyPlugin!"));

        // Register a scheduled job
        ctx.getService(VScheduler.class).ifPresent(s ->
            s.every("my-job", Duration.ofMinutes(5), this::doWork));
    }

    private void doWork() { /* ... */ }
}
```

Build against `vatn-api` only — never depend on `vatn-core` directly:

```xml
<dependency>
    <groupId>dev.vatn</groupId>
    <artifactId>vatn-api</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

A full step-by-step guide is in **[docs/dev-guide.md](docs/dev-guide.md)**.

---

## Examples

Twelve runnable projects in `examples/` — each a self-contained Maven module:

| # | Example | Concepts covered |
|---|---------|--------------------|
| [01](examples/01-hello-world/) | Hello World | Minimal plugin, single GET endpoint |
| [02](examples/02-rest-api/) | REST API (Task Manager) | CRUD, path params, JSON, SQLite persistence |
| [03](examples/03-websocket-chat/) | WebSocket Chat | Real-time bi-directional messaging, broadcast |
| [04](examples/04-dag-etl-pipeline/) | DAG ETL Pipeline | Workflow engine, operators, XCom, retry policy |
| [05](examples/05-realtime-dashboard/) | Real-time Dashboard | SSE, pub/sub, live metric streaming to browser |
| [06](examples/06-scheduled-report/) | Scheduled Report | Cron DAG, sensors, automated scheduled workflows |
| [07](examples/07-chat-app/) | Chat App | Full-stack HTML UI + WebSocket, rooms, history |
| [08](examples/08%20chaos-suite/) | Chaos Suite | OIPC watchdog, process restart, memory limits |
| [09](examples/09%20polyglot-ffi/) | Polyglot FFI | Java + C + Odin via Java 25 Panama (FFM) |
| [10](examples/10%20python-plugin/) | Python Plugin | Raw Python OIPC socket plugin |
| [11](examples/11-custom-guard/) | Custom Guard | PII redaction, keyword filtering, SSRF blocking |
| [12](examples/12-task-queue/) | Task Queue | Bull.js job-queue port — DAG as a crash-safe work queue |

### Run an example

```bash
cd examples/04-dag-etl-pipeline
mvn package -DskipTests
java -jar target/04-dag-etl-pipeline-1.0-SNAPSHOT.jar
```

See **[examples/README.md](examples/README.md)** for a full walkthrough of each example.

---

## Build from Source

```bash
git clone https://github.com/RainerXE/vatn.git
cd vatn

# Build everything (core + all plugins + web admin + examples)
mvn clean install -DskipTests

# Build only core + CLI
mvn clean install -pl vatn-api,vatn-core,vatn-cli --also-make -DskipTests

# Build only plugins
mvn clean compile -f plugins/pom.xml -DskipTests

# Build Web Admin only
mvn clean package -pl vatn-webadmin --also-make -DskipTests
```

### Prerequisites

- Java 25+ — GraalVM 25 recommended (`sdk install java 25.0.2-graal` via SDKMAN)
- Maven 3.9+

### Build modes

```bash
# JVM + Leyden AOT cache (~116 ms cold start)
mvn package verify -Pleyden -pl vatn-cli -am -DskipTests

# GraalVM native image (~24 ms cold start, no JVM required)
mvn clean package -Pnative -pl vatn-cli -am -DskipTests
./vatn-cli/target/vatn --version

# Web Admin native binary
mvn clean package -Pnative -pl vatn-webadmin -am -DskipTests
./vatn-webadmin/target/vatn-webadmin
```

| Mode | Cold start | JVM required | Distribution |
|------|-----------|--------------|--------------|
| JVM plain | ~144 ms | Yes (Java 25) | JAR (22 MB) |
| JVM + Leyden AOT | ~116 ms | Yes (same JVM) | JAR + cache (37 MB) |
| Native image | ~24 ms | No | Binary (113 MB) |

---

## Process Sandbox

VATN provides a layered sandboxing stack that any plugin can use. The entire stack sits in `vatn-core`; your plugin only sees `vatn-api` interfaces.

```
Plugin code
    │  ctx.getService(VSandboxProvider.class).exec("odin check .", 30)
    ▼
VSandboxProvider (vatn-api SPI)
    │  calls VGuardService.evaluateToolCall() first
    │  reads VatnSecurity.CURRENT_TRUST_LEVEL (ScopedValue)
    ▼
VProcessService.execute(cmd, env, dir, trustLevel)
    │
    ├─► ShellEnvPolicy.applyTo(pb.environment())      env isolation
    └─► OsSandboxWrapper.wrapCommand(cmd, trustLevel)  OS sandboxing
            macOS  → sandbox-exec -p "(deny file-write*)(deny network*)"
            Linux  → bwrap --ro-bind / / --unshare-all / --share-net
    ▼
VSubprocessAuditService.record(entry)                 audit log
```

### Trust Levels

| `VTrustLevel` | macOS sandbox | Linux sandbox | Use case |
|---|---|---|---|
| `FULL` | none | none | Trusted internal tools |
| `RESTRICTED` | deny file-write | ro-bind + share-net | Semi-trusted tools (network allowed) |
| `SANDBOXED` | deny file-write + deny network | ro-bind + unshare-all | Untrusted or user-supplied commands |

---

## Performance

Measured on Apple M5, `wrk -t8 -c256 -d30s`:

| Scenario | Throughput |
|----------|-----------|
| VATN `/ping` (plain text) | ~310,000 req/s |
| VATN `/json` (small JSON object) | ~280,000 req/s |
| Spring Boot 3 | ~243,000 req/s |
| Node.js / Express | ~78,000 req/s |
| Python / FastAPI | ~25,000 req/s |

DAG engine latency (JMH, warm JVM):

| Scenario | Avg latency |
|----------|------------|
| Single-task DAG trigger | ~0.8 ms |
| 10-task serial chain | ~9 ms |
| 10-task fan-out (parallel) | ~2 ms |
| Windmill (Python, 40 tasks) | ~2,400 ms |
| Apache Airflow (40 tasks) | ~56,000 ms |

---

## Project Modules

### `vatn-api` — The SPI (Start here)

Zero external dependencies. Every interface your plugin ever touches lives here.

| Interface | Purpose |
|-----------|---------| 
| `VNodePlugin` | Implement this; `onInitialize(ctx)` wires your services |
| `VNodeContext` | Entry point to every service |
| `VHttpService` / `VHttpRoutes` | REST endpoints and WebSocket handlers |
| `VMessaging` | In-process pub/sub (same API as cross-node OIPC) |
| `VDagEngine` / `VDagRegistry` | DAG workflow engine |
| `VQueueService` | Named work queues — claim/ack, DLQ, delayed jobs |
| `VTopicService` | Durable pub/sub — per-consumer offsets, replay |
| `VResourceLockService` | Advisory TTL locks |
| `VRateLimiter` | Token-bucket rate limiter |
| `VHttpClient` | Resilient outbound HTTP — retry, cache, circuit breaker |
| `VScheduler` | Cron / fixed-interval scheduler |
| `VBlobStore` | Content-addressed blob store |
| `VSecretService` | Encrypted secret storage |
| `VNodeIdentity` | Ed25519 node key pair, sign/verify |
| `VDiscovery` | LAN peer discovery |
| `VSandboxProvider` | OS-sandboxed subprocess execution |
| `VWasmRuntime` | Load and execute `.wasm` modules |
| `VWorkloadRegistry` | Global view of running DAG jobs, processes, containers |
| `workflow.*` | DAG model: `VDag`, `VDagTask`, `VOperator`, `VXCom` |

### `vatn-core` — The Runtime Engine

Helidon 4 SE–powered implementation of every `vatn-api` interface.

### `vatn-cli` — Developer Toolbelt

```
vatn run   [--port N] [--plugins <path>]   Start a VATN node
vatn init  [--lang java|python] <name>     Scaffold a new plugin project
vatn test  [--path <plugin-dir>]           Run the test harness against a plugin
vatn info                                  Print node info and loaded services
vatn registry                              Manage plugin registries
vatn oipc-benchmark                        OIPC wire-level latency benchmark
```

### `vatn-webadmin` — Web Admin Interface

Standalone bundled node combining Auth, Admin, and Containers plugins. Produces a fat JAR and a GraalVM native binary. Installs as a launchd / systemd user daemon.

### `plugins/` — Official Plugin Suite

24 drop-in plugins covering auth, databases, storage, messaging, WASM, containers, LLM clients, and more. See the [Plugin Catalog](#official-plugin-catalog) above.

### `examples/` — Runnable Examples

12 self-contained Maven projects covering the full feature surface. See [examples/README.md](examples/README.md).

### `vatn-bench` — Benchmarks

JMH micro-benchmarks and external load-test scripts (`bench/http/`, `bench/workflow/`, `bench/report/`).

### `vatn-spec` — Protocol Specifications

`OIPC-alignment.md` — Full VATN alignment document for the OIPC v2.12 "Relentless" + v2.13 wire protocol.

### `vatn-verify` — Protocol Verification

Standalone tools for verifying OIPC wire compliance: byte-level frame inspection, handshake validation.

### `docs/` — Documentation

| Document | Contents |
|----------|---------| 
| [dev-guide.md](docs/dev-guide.md) | **Start here.** Beginner's guide — install, build, first plugin, HTTP, DAG, security |
| [oipc-protocol.md](docs/oipc-protocol.md) | OIPC v2.12 binary wire spec |
| [vatn-architecture.md](docs/vatn-architecture.md) | Architecture and repo layout |

---

## OIPC Protocol

VATN nodes communicate over **OIPC (Octet IPC) v2.12 "Relentless"** — a lightweight binary/JSON protocol over Unix Domain Sockets (TCP fallback). You only need it directly to write plugins in Python, Rust, or C.

Wire format: 18-byte V3 header (`0x4F` magic, version, opcode, flags, length) + payload. Full spec: [docs/oipc-protocol.md](docs/oipc-protocol.md).

---

## Documentation Index

| | |
|--|--|
| **Beginner's guide** | [docs/dev-guide.md](docs/dev-guide.md) |
| **Examples** | [examples/README.md](examples/README.md) |
| **OIPC wire protocol** | [docs/oipc-protocol.md](docs/oipc-protocol.md) |
| **Architecture** | [docs/vatn-architecture.md](docs/vatn-architecture.md) |
| **API source** | `vatn-api/src/main/java/dev/vatn/api/` |
| **Benchmarks** | `vatn-bench/bench/` |

---

## License

VATN is released under the **MIT License**.

```
MIT License

Copyright (c) 2026 VATN Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Third-Party Dependencies

#### Runtime dependencies

| Library | Version | License | Used in |
|---------|---------|---------|---------| 
| [Eclipse Helidon SE](https://helidon.io/) | 4.5.0 | Apache 2.0 | HTTP, WebSocket, SSE (`vatn-core`) |
| [Jackson Databind](https://github.com/FasterXML/jackson-databind) | 2.16.1 | Apache 2.0 | JSON serialisation |
| [PF4J](https://pf4j.org/) | 3.9.0 | Apache 2.0 | Plugin loader and lifecycle |
| [Picocli](https://picocli.info/) | 4.7.5 | Apache 2.0 | CLI framework (`vatn-cli`) |
| [SLF4J](https://www.slf4j.org/) | 2.0.9 | MIT | Logging facade |
| [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) | 3.45.1.0 | Apache 2.0 | Embedded persistence |
| [Chicory](https://github.com/dylibso/chicory) | 1.7.5 | Apache 2.0 | WASM runtime (`vatn-plugin-wasm`) |
| [GraalVM SDK](https://www.graalvm.org/) | 24.1.1 | UPL 1.0 | Native image C ABI (compile-time) |

#### Test and benchmark dependencies

| Library | Version | License | Used in |
|---------|---------|---------|---------| 
| [JUnit Jupiter](https://junit.org/junit5/) | 5.10.0 | EPL 2.0 | Unit and integration tests |
| [Awaitility](https://github.com/awaitility/awaitility) | 4.2.1 | Apache 2.0 | Async test assertions |
| [JMH](https://github.com/openjdk/jmh) | 1.37 | GPL v2 + Classpath Exception | Micro-benchmarks (`vatn-bench`) |

> **Note on JMH**: Used exclusively in `vatn-bench` and never shipped as part of the runtime.

---
