# VATN — Virtual Application Transaction Node

**A high-performance, plugin-native JVM runtime for building distributed applications and agentic systems.**

VATN gives you the developer ergonomics of Node.js — one-liner server start, drop-in plugins, built-in pub/sub — but runs on the JVM with Java 25 virtual threads, delivering throughput comparable to Go or Rust while shipping a full production stack out of the box: HTTP, WebSocket, DAG workflows, distributed tracing, secrets, cryptographic identity, and LAN federation.

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
| Secrets | dotenv / Vault SDK | Spring Vault | **`VSecretService` — AES-256-GCM, filesystem-backed, vault-ready SPI** |
| Node identity | None | None | **Ed25519 key pair per node, sign/verify data** |
| Federation | None | None | **UDP LAN discovery (v1); full lattice mesh (v2)** |
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
 │             Your plugins  /  vatn-plugin-*                           │
 │   your own VNodePlugin impls  ·  vatn-plugins ecosystem              │
 └──────────────────────────────────────────────────────────────────────┘

 ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────┐
 │  vatn-cli    │  │  vatn-bench  │  │  vatn-test   │  │ vatn-spec  │
 │  vatn run    │  │  JMH / wrk   │  │  harness     │  │ OIPC spec  │
 │  vatn init   │  │  benchmarks  │  │              │  │            │
 │  vatn test   │  └──────────────┘  └──────────────┘  └────────────┘
 └──────────────┘
```

---

## Project Modules

### `vatn-api` — The SPI (Start here)

Zero external dependencies. Every interface your plugin ever touches lives here. You compile your plugin against `vatn-api` only; the runtime (`vatn-core`) is provided by the node.

Key surfaces:

| Interface | Purpose |
|-----------|---------|
| `VNodePlugin` | Implement this; `onInitialize(ctx)` wires your services |
| `VNodeContext` | Entry point to every service: `ctx.getMessaging()`, `ctx.getService(VDagEngine.class)`, … |
| `VHttpService` / `VHttpRoutes` | Declare REST endpoints and WebSocket handlers |
| `VMessaging` | In-process pub/sub; same API as cross-node OIPC in v2 |
| `VDagEngine` / `VDagRegistry` | Define and trigger DAG workflows |
| `VGuardService` | Intercept input, output, and tool calls for PII / SSRF filtering |
| `VSecretService` | Store and retrieve encrypted secrets |
| `VNodeIdentity` | Sign and verify data with the node's Ed25519 key |
| `VDiscovery` / `VNameResolver` | LAN peer discovery (v1) and name resolution |
| `VTracingService` | Distributed tracing (noop by default; OTLP via `VATN_OTLP_ENDPOINT`) |
| `workflow.*` | Full DAG model: `VDag`, `VDagTask`, `VOperator`, `VXCom`, `VPool`, `VEventLog` |
| `security.*` | `VFirewall`, `VFlowPolicy`, `VPolicyInterjector`, `VTrustLevel`, `VSecretService` |

### `vatn-core` — The Runtime Engine

The Helidon 4 SE–powered implementation of every `vatn-api` interface. Depends on Helidon, PF4J, Jackson, SQLite, and SLF4J. You never depend on this directly in your plugin — it is the runtime that runs your plugin.

Notable internals:

- **`VNodeRunner`** — one-liner bootstrap; wires HTTP router, plugin lifecycle, DAG engine, OIPC transport, and all platform services
- **`VDagEngineImpl`** — Airflow-style task graph execution on virtual threads; SQLite persistence; crash-safe replay via `VEventLog`
- **`OipcMessagingTransport`** — OIPC v2.12 binary protocol over Unix Domain Sockets (TCP fallback); full HELLO handshake; async virtual-thread accept loop
- **`VNativeBridge`** — GraalVM `@CEntryPoint` C ABI; exposes `vatn_node_start`, `vatn_node_stop`, `vatn_call`, `vatn_get_diagnostics` to C/Rust callers
- **`VRegistry`** — PF4J-based plugin loader with Ed25519 JAR signature verification; trust-level assignment (SANDBOXED → RESTRICTED → FULL)

### `vatn-cli` — Developer Toolbelt

```
vatn run   [--port N] [--plugins <path>]   Start a VATN node
vatn init  [--lang java|python] <name>     Scaffold a new plugin
vatn test  [--path <plugin-dir>]           Run the test harness against a plugin
vatn info                                  Print node info and loaded services
vatn registry                              Manage remote plugin registries
vatn oipc-benchmark                        Run the OIPC wire-level latency benchmark
```

Built with Picocli; produces a single fat JAR. A GraalVM native binary is the intended distribution format.

### `vatn-bench` — Benchmarks

JMH micro-benchmarks and external load-test scripts:

| Benchmark | What it measures |
|-----------|-----------------|
| `WorkflowDagBench` | DAG trigger latency, fan-out throughput, XCom pipeline, crash-resume overhead |
| `JsonBench` | Jackson parse/stringify throughput, VJson query |
| `HttpServerBench` | In-process HTTP handler throughput |
| `bench/http/run.sh` | External wrk load against a live VATN node |
| `bench/workflow/run.sh` | DAG throughput vs Windmill / Prefect / Airflow |
| `bench/report/generate.sh` | Aggregate all results into a dated Markdown report |

### `examples/` — Working Examples

Eleven runnable projects, each a self-contained Maven module:

| # | Example | Concepts covered |
|---|---------|-----------------|
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

### `vatn-test` — Plugin Test Harness

Standalone harness for black-box testing a VATN plugin. Boots a minimal node, loads the plugin under test, runs assertions against its HTTP surface and messaging output. Suitable for CI without mocking the runtime.

### `vatn-spec` — Protocol Specifications

- `OIPC-212-alignment.md` — Full VATN alignment document for the OIPC v2.12 "Relentless" wire protocol: 18-byte V3 binary header, HELLO handshake, VOipcMessageType opcode table, shutdown RELAXED/STRICT, trust-level enforcement, policy resolution

### `vatn-verify` — Protocol Verification Tools

Standalone tools for verifying OIPC wire compliance: byte-level frame inspection, handshake validation, and message-type conformance checks. Used by CI and plugin authors to validate custom OIPC implementations.

### `docs/` — Documentation

| Document | Contents |
|----------|---------|
| [dev-guide.md](docs/dev-guide.md) | **Start here.** Beginner's guide — install, build, first plugin, HTTP, DAG, security. Analogies to Node.js throughout. |
| [oipc-protocol.md](docs/oipc-protocol.md) | OIPC v2.12 binary wire spec — header layout, opcodes, HELLO handshake, lifecycle channels |
| [vatn-architecture.md](docs/vatn-architecture.md) | VATN/Applications boundary, repo layout, dev workflow |

---

## Quick Start

### Prerequisites

- Java 25+ (OpenJDK 25 or GraalVM 25)
- Maven 3.9+
- **GraalVM 25** (Oracle GraalVM 25.0.2+, required for native image builds)

### Build everything

```bash
git clone <vatn-repo>
cd vatn
mvn clean install -DskipTests
```

### Build options: JVM + Leyden, or native image

VATN supports three runtime modes. All require GraalVM 25 (install once via SDKMAN):

```bash
sdk install java 25.0.2-graal
sdk use java 25.0.2-graal
```

**Option A — JVM + Project Leyden AOT cache** (~116 ms cold start, ~15 MB cache file)

```bash
# Build the JAR and generate the Leyden cache in one step
mvn package verify -Pleyden -pl vatn-cli -am -DskipTests
# → vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar  (fat JAR, 22 MB)
# → vatn-cli/target/vatn.aot                   (AOT cache, 15 MB)

# Run with the cache (printed at build end)
~/.sdkman/candidates/java/current/bin/java \
  -XX:AOTCache=vatn-cli/target/vatn.aot \
  -jar vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar --help

# Or use the convenience script
./vatn-cli/leyden-cache.sh run vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar --help
```

**Option B — GraalVM native image** (~24 ms cold start, ~113 MB single binary, no JVM required)

```bash
mvn clean package -Pnative -pl vatn-cli -am -DskipTests
# → vatn-cli/target/vatn  (self-contained binary)

./vatn-cli/target/vatn --version
# VATN Runtime 1.0.0
```

| Mode | Cold start | JVM required | Distribution |
|------|-----------|--------------|--------------|
| JVM plain | ~144 ms | Yes (Java 25) | JAR (22 MB) |
| JVM + Leyden AOT | ~116 ms | Yes (same JVM) | JAR + cache (37 MB) |
| Native image | ~24 ms | No | Binary (113 MB) |

> **Plugin model in native mode:** Dynamic JAR loading is disabled in native image. Plugins ship compiled-in (Path A) or as separate OIPC processes (Path B). See [docs/dev-guide.md § Native image](docs/dev-guide.md#19-native-image).

### Run the hello-world example

```bash
cd examples/01-hello-world
mvn package -DskipTests
java -jar target/01-hello-world-1.0-SNAPSHOT.jar
# → http://localhost:8080/hello
```

### Your first plugin from scratch

```java
// HelloPlugin.java
public class HelloPlugin implements VNodePlugin {
    public String getId()      { return "com.example.hello"; }
    public String getName()    { return "Hello VATN"; }
    public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.register("/hello", routes -> routes
            .get("/",       (req, res) -> res.send("Hello from VATN!"))
            .get("/{name}", (req, res) ->
                res.sendJson("{\"msg\":\"Hello, " + req.pathParam("name") + "!\"}")));
    }

    @Override
    public void onShutdown() {}
}

// Main.java
public class Main {
    public static void main(String[] args) throws Exception {
        VNodeRunner.create(8080)
            .addPlugin(new HelloPlugin())
            .start();
    }
}
```

```
$ curl http://localhost:8080/hello/world
{"msg":"Hello, world!"}
```

Full step-by-step walkthrough, analogies to Node.js, DAG workflows, security, and deployment: **[docs/dev-guide.md](docs/dev-guide.md)**

---

## OIPC Protocol

VATN nodes communicate over **OIPC (Octet IPC) v2.12 "Relentless"** — a lightweight binary/JSON protocol over Unix Domain Sockets (TCP fallback). The Java `VNodePlugin` SPI abstracts OIPC entirely; you only need it directly to write plugins in Python, Rust, or C.

Wire format: 18-byte V3 header (`0x4F` magic, version, opcode, flags, length) + payload. Full spec: [docs/oipc-protocol.md](docs/oipc-protocol.md).

---

## Federation (v1 now, v2 roadmap)

Every VATN node runs `VUdpDiscovery` at startup: it joins the `224.0.0.251:7719` multicast group and announces itself every 5 seconds. `VDiscovery` and `VNameResolver` are live services — nodes on the same LAN find each other automatically.

**v2 (VATN Lattice)** upgrades this to production-grade federation: cryptographic node bonding, encrypted cross-node channels, federated DAG execution with `nodeAffinity`, distributed `VMessaging` across nodes, and a gossip-based mesh. Your plugin code does not change — `VNodeContext` is the same interface.

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

## Ecosystem

| Repository | Purpose |
|------------|---------|
| [vatn-demo](https://github.com/RainerXE/vatn-demo) | Ports of well-known systems (Bull.js, Celery, Express, …) to VATN — with step-by-step migration tutorials |
| [vatn-plugins](https://github.com/RainerXE/vatn-plugins) | Drop-in plugins: JWT auth (`vatn-plugin-auth`), security headers (`vatn-plugin-security`), stream indexer (`vatn-plugin-indexer`), web scraper (`vatn-plugin-scraper`) |

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

VATN bundles or depends on the following open-source libraries. All are permissively
licensed and compatible with the MIT License.

#### Runtime dependencies

| Library | Version | License | Used in |
|---------|---------|---------|---------|
| [Eclipse Helidon SE](https://helidon.io/) | 4.4.1 | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) | HTTP, WebSocket, SSE, tracing (`vatn-core`) |
| [Jackson Databind](https://github.com/FasterXML/jackson-databind) | 2.16.1 | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) | JSON serialisation (`vatn-core`) |
| [PF4J](https://pf4j.org/) | 3.9.0 | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) | Plugin loader and lifecycle (`vatn-core`, plugins) |
| [Picocli](https://picocli.info/) | 4.7.5 | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) | CLI command framework (`vatn-cli`) |
| [SLF4J](https://www.slf4j.org/) | 2.0.9 | [MIT](https://opensource.org/licenses/MIT) | Logging facade and simple backend (all modules) |
| [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) | 3.45.1.0 | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) | Embedded persistence (`vatn-core`) |
| [PostgreSQL JDBC](https://jdbc.postgresql.org/) | 42.7.2 | [BSD 2-Clause](https://opensource.org/licenses/BSD-2-Clause) | Optional Postgres backend (`vatn-core`) |
| [Flyway Community](https://flywaydb.org/) | 10.8.1 | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) | Database schema migrations (`vatn-core`) |
| [GraalVM SDK](https://www.graalvm.org/) | 24.1.1 | [UPL 1.0](https://opensource.org/licenses/UPL) | Native image C ABI (`vatn-core`, compile-time) |

#### Test and benchmark dependencies

| Library | Version | License | Used in |
|---------|---------|---------|---------|
| [JUnit Jupiter](https://junit.org/junit5/) | 5.10.0 | [EPL 2.0](https://opensource.org/licenses/EPL-2.0) | Unit and integration tests |
| [Awaitility](https://github.com/awaitility/awaitility) | 4.2.1 | [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) | Async test assertions |
| [JMH](https://github.com/openjdk/jmh) | 1.37 | [GPL v2 + Classpath Exception](https://openjdk.org/legal/gplv2+ce.html) | Micro-benchmarks (`vatn-bench`) |

> **Note on JMH**: JMH is licensed under GPL v2 with the Classpath Exception, which permits
> use as a tool dependency without GPL propagation to application code. It is used exclusively
> in `vatn-bench` and is never shipped as part of the VATN runtime.

---

