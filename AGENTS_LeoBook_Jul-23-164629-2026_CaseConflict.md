# VATN — Agent Guide

This document is the primary orientation for AI coding agents working on the VATN codebase. Read it before touching any file.

---

## What this project is

VATN (Virtual Application Transaction Node) is a plugin-native JVM runtime for distributed applications and agentic systems. Think Node.js ergonomics (one-liner server start, drop-in plugins, built-in pub/sub) but on the JVM with Java 25 virtual threads.

The core value proposition:
- **`VNodePlugin` SPI** — implement one interface, get HTTP, WebSocket, DAG workflows, pub/sub, secrets, identity, and tracing for free
- **OIPC v2.12** — language-agnostic binary protocol over Unix Domain Sockets; Python, C, Odin, Rust can all speak it
- **GraalVM native image** — first-class support; the CLI ships as a 113 MB binary with ~24 ms cold start
- **Project Leyden AOT** — JVM mode with ~116 ms cold start using Java 25 AOT code cache

---

## Repository layout

```
vatn-api/          Zero-dependency SPI — the only module plugins ever depend on
vatn-core/         Runtime implementation (Helidon, PF4J, Jackson, SQLite)
vatn-cli/          Developer CLI (Picocli fat JAR + GraalVM native binary)
vatn-bench/        JMH micro-benchmarks + external load-test scripts
vatn-test/         Plugin test harness (black-box node boot for CI)
vatn-verify/       OIPC wire-compliance verification tools
vatn-spec/         Protocol alignment docs
vatn-bom/          BOM for version management only (no code)
vatn-plugin-indexer/   Reference plugin: stream indexer
vatn-plugin-scraper/   Reference plugin: web scraper
examples/          11 runnable Maven examples (self-contained)
docs/              dev-guide.md, oipc-protocol.md, vatn-architecture.md
```

---

## The one rule that overrides everything else

**Plugins must only depend on `vatn-api`.** Never add a `vatn-core` dependency to a plugin or example module. `vatn-core` is injected by the runtime at load time. Coupling a plugin to core internals breaks hot-swap, native-image compatibility, and the entire trust model.

---

## Build commands

```bash
# Full build (skip tests for speed)
mvn clean install -DskipTests

# Build + test
mvn clean install

# Run a specific module's tests
mvn test -pl vatn-core

# Native image CLI binary (~3–5 min, requires GraalVM 25)
sdk use java 25.0.2-graal
mvn clean package -Pnative -pl vatn-cli -am -DskipTests
# → vatn-cli/target/vatn

# JVM fat JAR + Leyden AOT cache
mvn package verify -Pleyden -pl vatn-cli -am -DskipTests
# → vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar
# → vatn-cli/target/vatn.aot

# Build only vatn-cli JAR (fast iteration)
mvn package -pl vatn-cli -am -DskipTests
```

**Required JVM: GraalVM 25 (Oracle GraalVM 25.0.2+)**
```bash
sdk install java 25.0.2-graal
sdk use java 25.0.2-graal
```
Do not use Homebrew OpenJDK or any OpenJDK for this project. The Leyden AOT cache is JVM-build-specific and will silently break if you mix JVM builds.

---

## Key classes and where to find them

| Class | Module | Purpose |
|-------|--------|---------|
| `VNodeRunner` | vatn-core | Bootstrap — `.create(port).addPlugin(p).start()` |
| `VNodeContextImpl` | vatn-core | Service registry; `ConcurrentHashMap<Class<?>, Object>` |
| `VRegistry` | vatn-core | PF4J plugin loader + Ed25519 JAR signature verifier |
| `OipcMessagingTransport` | vatn-core | OIPC v2.12 binary protocol; UDS with TCP fallback |
| `VDagEngineImpl` | vatn-core | DAG workflow executor; SQLite-backed, crash-safe |
| `VNativeBridge` | vatn-core | GraalVM `@CEntryPoint` C ABI |
| `NativeImagePluginManager` | vatn-core | No-op PF4J replacement active in native image mode |
| `VatnCLI` | vatn-cli | Picocli root command; entry point for the CLI binary |
| `VNodePlugin` | vatn-api | The interface every plugin implements |
| `VNodeContext` | vatn-api | The interface every plugin's `onInitialize` receives |

---

## Module dependency rules

```
vatn-bom    → (none)
vatn-api    → (no external deps)
vatn-core   → vatn-api, Helidon 4 SE, PF4J, Jackson, sqlite-jdbc, SLF4J, GraalVM SDK
vatn-cli    → vatn-api, vatn-core (shaded), Picocli
vatn-bench  → vatn-api, vatn-core, JMH
vatn-test   → vatn-api, vatn-core
vatn-verify → vatn-api, vatn-core
plugins     → vatn-api ONLY  ← inviolable
examples    → vatn-api, vatn-core (shaded into fat JAR)
```

---

## Adding a new platform service

1. Define the interface in `vatn-api`: extend `VService`, annotate `@VatnApi(since = "X.X")`.
2. Implement in `vatn-core`: `VMyServiceImpl implements VMyService`.
3. Register in `VNodeRunner.start()`: `context.registerService(VMyService.class, new VMyServiceImpl(...))`.
4. Optionally add a typed accessor to `VNodeContext` / `VNodeContextImpl`.
5. Add native-image reflection config in `vatn-core/src/main/resources/META-INF/native-image/dev.vatn/vatn-core/reflect-config.json` if the impl is instantiated reflectively.
6. Write tests in `vatn-core/src/test`.
7. Add a row to the Service SPI table in `docs/dev-guide.md`.

## Adding a new CLI command

1. Create `dev.vatn.cli.commands.MyCommand` in `vatn-cli`, annotate with `@Command`.
2. Register it on `VatnCLI` with `@Command(subcommands = {..., MyCommand.class})`.
3. Update `vatn-cli/src/main/resources/META-INF/native-image/dev.vatn.cli/reflect-config.json` — add an entry for the new command class.
4. Test with both JVM and native: `./vatn-cli/target/vatn my-command --help`.

---

## Native image constraints

The native image build uses the closed-world assumption — no runtime class loading.

**What this means in practice:**
- Dynamic PF4J JAR scanning is disabled. `VRegistry` detects `ImageInfo.inImageCode()` and swaps in `NativeImagePluginManager` (a no-op).
- Plugins in native mode are either compiled-in (add dependency + `addPlugin()`) or run out-of-process as separate binaries communicating over OIPC v2.12.
- Any new class instantiated reflectively (e.g., Jackson deserialisation targets, Picocli commands) must be registered in the appropriate `reflect-config.json`.
- SLF4J and all its helpers must be `--initialize-at-build-time` — see `vatn-core/src/main/resources/META-INF/native-image/dev.vatn/vatn-core/native-image.properties`.
- Do not add `--initialize-at-run-time` for any class that the `SqliteJdbcFeature` already marks as build-time.

Native image config files:
- `vatn-core/src/main/resources/META-INF/native-image/dev.vatn/vatn-core/` — reflect, resource, native-image.properties
- `vatn-cli/src/main/resources/META-INF/native-image/dev.vatn.cli/` — reflect, native-image.properties

---

## OIPC protocol

OIPC v2.12 "Relentless" — 18-byte V3 binary header:
```
[0x4F magic][version][opcode][flags][4-byte length][8-byte correlation-id]
```
Full spec: `docs/oipc-protocol.md`. The Java transport is `OipcMessagingTransport`. OIPC clients in Python/C/Odin are in `examples/09 polyglot-ffi/` and `examples/10 python-plugin/`.

For any OIPC changes: the handshake requires `Major = 2`. The `HELLO` opcode must be the first frame. Trust level is negotiated at handshake time and enforced on every subsequent frame by `VFlowPolicy`.

---

## Security model

Every plugin gets a `VTrustLevel`:
- `FULL` — programmatically registered via `addPlugin()` (trusted by the developer)
- `RESTRICTED` — JAR with a valid Ed25519 signature in `META-INF/vatn.sig`
- `SANDBOXED` — unsigned JAR; most restricted
- `NONE` — invalid signature; refused, never started

`VatnSecurity.CURRENT_PLUGIN_ID` is a `ScopedValue`. It is set by `VNodeRunner` before any plugin method and propagates through all virtual-thread forks within that scope. Never pass plugin identity as a parameter — read it from the ScopedValue.

`VPolicyInterjector` implementations run at every `VStream.createPolicyStream()` call. Return `DENY` to block, `ALLOW` to permit, `PASS` to defer to the next interjector.

---

## DAG workflow engine

DAGs are defined as `VDag` objects containing `VDagTask` nodes. Each task has an `operator` (`VOperator` implementation), a `retryPolicy`, and optional XCom references.

Key rules:
- State machine: `NONE → RUNNING → SUCCESS/FAILED`. Retries re-enter `RUNNING`. After `maxRetries`, the task is terminal `FAILED` and unblocked upstreams are marked `UPSTREAM_FAILED`.
- The engine polls at 50 ms intervals — do not rely on sub-50 ms task sequencing in tests.
- `resumeInterruptedRuns()` is called at startup; tasks with a persisted `TASK_SUCCESS` event are skipped (idempotent replay).
- XCom values are scoped to a `VDagRun`. Never share state across runs via XCom.
- SQLite tables are `CREATE TABLE IF NOT EXISTS` — always safe to run on startup.

---

## Testing

```bash
mvn test                            # all tests
mvn test -pl vatn-core              # core tests only
mvn test -pl vatn-core -Dtest=VDagEngineTest  # single test class
```

Tests are JUnit 5 + Awaitility. For async assertions always use `Awaitility.await()` — never `Thread.sleep()` in assertions.

The `VNodeContextImpl(nodeId, firewall, initialServices)` constructor accepts a pre-built service map — use it in tests to inject mocks without booting a real node.

For OIPC tests, `vatn-verify` tools can do byte-level frame inspection without a full node.

---

## Naming conventions

| Thing | Convention | Example |
|-------|-----------|---------|
| SPI interfaces | `V` prefix | `VNodePlugin`, `VDagEngine` |
| Core implementations | `V...Impl` suffix | `VDagEngineImpl` |
| CLI commands | `...Command` suffix | `RunCommand`, `InfoCommand` |
| Native bridge methods | `vatn_` prefix (C ABI) | `vatn_node_start` |
| FFI shared library symbols | `vatn_` prefix | `vatn_path_verdict` |
| Pub/sub topics | dot-separated | `orders.created`, `vatn.monitor.security` |
| Built-in HTTP paths | `/vatn/` prefix | `/vatn/health`, `/vatn/ready` |
| SQLite tables | `vatn_` prefix | `vatn_dag_runs`, `vatn_event_log` |

---

## Common pitfalls

- **Do not call `System.exit()` from plugin code.** The node lifecycle manages shutdown via `VLifecycle`.
- **Do not use `Thread.sleep()` in DAG operators.** Use `VScheduler` or cron DAGs.
- **Do not add Jackson annotations to `vatn-api` types.** The API has zero external dependencies.
- **Do not use `Class.forName()` or `Method.invoke()` in paths that run in native image.** Register in reflect-config if you must.
- **Do not depend on `vatn-core` from plugin code** — only `vatn-api`.
- **Do not mix JVM builds when using the Leyden AOT cache.** The cache is tied to the exact JVM binary that created it. Always use SDKMAN GraalVM 25.
- **Do not initialise SLF4J loggers as static fields in classes that will be `--initialize-at-run-time`.** Move the logger to instance scope or switch the class to build-time init.
- **Do not change the OIPC wire format without updating `docs/oipc-protocol.md` and `vatn-spec/OIPC-alignment.md`.**

---

## Useful references

| Document | Location |
|----------|---------|
| Developer getting-started guide | `docs/dev-guide.md` |
| OIPC v2.12 wire protocol spec | `docs/oipc-protocol.md` |
| Architecture deep-dive | `docs/vatn-architecture.md` |
| AI adversarial review ritual + findings log | `docs/security/adversarial-review-ritual.md` / `docs/security/findings-log.md` |
| Threat model | `docs/threat-model.md` |
| Native image config | `vatn-core/src/main/resources/META-INF/native-image/` |
| Native-image build script | `build-native.sh` (post-install — replaces launchers with native binaries) |
| Leyden convenience script | `vatn-cli/leyden-cache.sh` |
| Working examples | `examples/01-hello-world/` … `examples/11-custom-guard/` |
| Polyglot FFI examples | `examples/09 polyglot-ffi/` |
