# VATN Developer Guide

> **Version:** VATN 1.0 · Java 25 · Helidon 4 SE  
> **Who this is for:** Anyone who has built a web service before — in Node.js, Spring Boot, Python, or anything else — and wants to understand what VATN is, how it works, and how to build on it from scratch.

---

## Table of Contents

1. [What is VATN and why does it exist?](#1-what-is-vatn-and-why-does-it-exist)
2. [VATN vs. Node.js — a translation guide](#2-vatn-vs-nodejs--a-translation-guide)
3. [What VATN does beyond Node.js and Helidon](#3-what-vatn-does-beyond-nodejs-and-helidon)
4. [Prerequisites and setup](#4-prerequisites-and-setup)
5. [Building VATN from source](#5-building-vatn-from-source)
6. [Hello World — your first VATN app](#6-hello-world--your-first-vatn-app)
7. [HTTP services and routing](#7-http-services-and-routing)
8. [WebSocket and Server-Sent Events](#8-websocket-and-server-sent-events)
9. [Pub/Sub messaging](#9-pubsub-messaging)
10. [Named work queues (VQueueService)](#10-named-work-queues-vqueueservice)
11. [Durable pub/sub topics (VTopicService)](#11-durable-pubsub-topics-vtopicservice)
12. [Advisory locks (VResourceLockService)](#12-advisory-locks-vresourcelockservice)
13. [Persisting data](#13-persisting-data) — including DB isolation for apps built on VATN
14. [Background jobs with the DAG engine](#14-background-jobs-with-the-dag-engine)
15. [Secrets and configuration](#15-secrets-and-configuration)
16. [Security: guard policies and trust levels](#16-security-guard-policies-and-trust-levels)
17. [Node identity and signatures](#17-node-identity-and-signatures)
18. [Observability: health, tracing, and rate limiting](#18-observability-health-tracing-and-rate-limiting)
19. [Running the examples](#19-running-the-examples)
20. [Service SPI reference](#20-service-spi-reference)
21. [Benchmarks](#21-benchmarks)
22. [Native image](#22-native-image)
23. [Project Leyden — JVM AOT cache](#23-project-leyden--jvm-aot-cache)

---

## 1. What is VATN and why does it exist?

If you have used Node.js, you know the appeal: one command installs everything, one line starts a server, and you get a plugin ecosystem (npm) that covers almost any need. The downside is that JavaScript is single-threaded, and once your service starts doing real work — CPU-heavy parsing, thousands of concurrent connections, or background pipelines — you fight the event loop.

**VATN is an attempt to take that developer experience and rebuild it on the JVM**, where:

- Every connection runs on a **virtual thread** (Java 25 Project Loom), so you write blocking `in.read()` code that scales to hundreds of thousands of concurrent connections without callback hell or `async/await`.
- The HTTP engine is **Helidon 4 SE** — one of the fastest JVM HTTP stacks, competing with Go and Rust at the wire level.
- The plugin system, messaging, workflow engine, secrets, identity, and federation are **built in** — not assembled from a dozen npm packages with incompatible lifecycles.

The result is a server runtime that feels like Node.js to write but performs like a compiled systems language and ships production infrastructure out of the box.

### The Node.js analogy

Think of VATN like this:

```
Node.js + Express + Socket.IO + Bull + node-cron + dotenv + Passport
         ≈
VATN (single dependency, one JVM process, one config, one plugin model)
```

The difference is not just consolidation. VATN's workflow engine (`VDagEngine`) is crash-safe and replays interrupted runs from a durable log — something you cannot get from Bull without external Redis and careful coding. VATN's security model assigns cryptographic trust levels to plugins at load time and enforces policies at every service boundary. And VATN nodes discover each other on the LAN automatically, giving you multi-node development without a service registry.

---

## 2. VATN vs. Node.js — a translation guide

If you know Node.js, you already know how to think about VATN. Here is the direct translation:

### Starting a server

**Node.js / Express**
```javascript
const express = require('express');
const app = express();
app.listen(8080, () => console.log('Listening on 8080'));
```

**VATN**
```java
VNodeRunner.create(8080).start();
```

Both are one-liners. The difference is that `VNodeRunner.start()` also boots the plugin registry, OIPC transport, DAG engine, and all platform services simultaneously.

> **Building on VATN?** If your application is a product built *on top of* VATN (rather than a tool shipped as part of the framework), store your database in your own directory rather than the VATN default `~/.vatn/database.db`. See [§13 — Persisting data](#13-persisting-data) for details.

### Defining routes

**Node.js / Express**
```javascript
app.get('/hello', (req, res) => res.send('Hello!'));
app.get('/hello/:name', (req, res) => res.json({ msg: `Hello, ${req.params.name}!` }));
```

**VATN**
```java
context.register("/hello", routes -> routes
    .get("/",       (req, res) -> res.send("Hello!"))
    .get("/{name}", (req, res) ->
        res.sendJson("{\"msg\":\"Hello, " + req.pathParam("name") + "!\"}")));
```

The `VHttpService` / `VHttpRoutes` API maps directly to Express's router. Path parameters use `{name}` instead of `:name`.

### Middleware / plugins

**Node.js**
```javascript
app.use(myMiddleware);
```

**VATN**  
There is no global middleware chain. Instead, you implement `VNodePlugin` — VATN's equivalent of an Express app module — and register it before starting the node:

```java
VNodeRunner.create(8080)
    .addPlugin(new MyPlugin())
    .addPlugin(new AnotherPlugin())
    .start();
```

Each plugin is isolated. VATN assigns it a trust level, runs its `onInitialize` with a scoped security context, and calls `onShutdown` when the node stops.

### EventEmitter / pub/sub

**Node.js**
```javascript
const emitter = new EventEmitter();
emitter.on('data.ready', handler);
emitter.emit('data.ready', payload);
```

**VATN**
```java
VMessaging messaging = ctx.getMessaging();
messaging.subscribe("data.ready", bytes -> handle(bytes));
messaging.publish("data.ready", payload.getBytes());
```

Subscribers run on virtual threads — no callback nesting, no `.then()` chains.

### Background jobs

**Node.js / Bull**
```javascript
const queue = new Bull('etl');
queue.process(async job => { /* ... */ });
queue.add({ date: '2026-05-26' });
```

**VATN**
```java
VDagRegistry registry = ctx.getService(VDagRegistry.class).orElseThrow();
registry.registerOperator(new VOperator() {
    public String operatorType() { return "etl"; }
    public String execute(VTaskContext ctx) throws Exception {
        String date = ctx.getConf().getOrDefault("date", "today");
        // ... do work
        return "done";
    }
});
VDag job = VDag.manual("etl", "ETL job",
    Map.of("step", VDagTask.of("step", "etl", Set.of(), Map.of())));
registry.register(job);

VDagEngine engine = ctx.getService(VDagEngine.class).orElseThrow();
engine.trigger("etl", Map.of("date", "2026-05-26"), true);
```

This looks like more code, but you get: multi-step graphs, upstream dependencies, retry policies, XCom data passing between steps, cron scheduling, and crash-safe replay — all without Redis.

### Cron jobs

**Node.js / node-cron**
```javascript
cron.schedule('0 2 * * *', () => generateReport());
```

**VATN**
```java
VDag nightly = VDag.scheduled("nightly-report", "Nightly report",
    "0 2 * * *",
    Map.of("generate", VDagTask.of("generate", "report-gen", Set.of(), Map.of())));
registry.register(nightly);
// The VDagScheduler picks it up — no further wiring.
```

### Environment variables / config

**Node.js / dotenv**
```javascript
require('dotenv').config();
const key = process.env.API_KEY;
```

**VATN**
```java
VConfiguration config = ctx.getConfiguration();
String key = config.get("API_KEY").orElse("default");

// Or encrypted secrets:
VSecretService secrets = ctx.getService(VSecretService.class).orElseThrow();
String apiKey = secrets.get("api.key").orElseThrow();
```

`VConfiguration` reads from environment variables and `vatn.yaml`. `VSecretService` stores secrets encrypted with AES-256-GCM — they are never plaintext on disk.

### Streams / piping

**Node.js**
```javascript
fs.createReadStream('input.json').pipe(transform).pipe(res);
```

**VATN**
```java
VStream stream = ctx.getStream();
OutputStream out = stream.createOutput("my-stream");
InputStream in = stream.openInput("my-stream");
stream.pipe(in, out); // runs on a virtual thread
```

Cross-node piping is just `stream.createRemoteOutput("http://other-node/stream/id")` — the runtime handles the relay automatically.

---

## 3. What VATN does beyond Node.js and Helidon

These are capabilities that have no direct equivalent in Node.js and that you would normally have to assemble from separate systems:

### Crash-safe DAG replay

If your process dies mid-pipeline — power outage, OOM, deployment — VATN knows exactly which tasks completed and which did not. On the next start, call:

```java
engine.resumeInterruptedRuns();
```

Tasks that already succeeded are skipped. Only the tasks that were running or not yet started are re-executed. This is backed by a durable append-only event log in SQLite — no external state store required.

### Cryptographic node identity

Every VATN node generates an Ed25519 keypair on first boot, stored at `~/.vatn/.identity` with owner-only filesystem permissions. You can sign and verify data in any plugin:

```java
VNodeIdentity identity = ctx.getService(VNodeIdentity.class).orElseThrow();
byte[] signature = identity.sign(data);
boolean valid = identity.verify(data, signature, identity.getPublicKey());
```

This is the foundation for the v2 federation lattice — nodes can cryptographically verify each other before routing traffic.

### Plugin trust levels

When VATN loads a plugin JAR, it inspects it for a VATN signature bundle (`META-INF/vatn.sig` + `META-INF/vatn.key`). Based on the result, it assigns one of:

| Level | Meaning |
|-------|---------|
| `NONE` | Signature present but invalid — plugin refused |
| `SANDBOXED` | Unsigned — loaded but blocked from privileged APIs |
| `RESTRICTED` | Signed — limited cross-plugin access |
| `FULL` | Registered directly in code — fully trusted |

Trust levels are enforced at `VStream.createPolicyStream` and at every `VPolicyInterjector` decision point. A sandboxed plugin cannot open a stream that requires RESTRICTED trust — the runtime throws before any data flows.

### Policy interjectors

Register a `VPolicyInterjector` on the stream service to intercept and approve or deny every flow request before it starts:

```java
streamService.addInterjector((pluginId, policy) -> {
    if (pluginId.startsWith("untrusted.")) return Decision.DENY;
    return Decision.ALLOW;
});
```

Denials are published to the `vatn.monitor.security` OIPC channel as structured `PolicyViolation` events — ready to be consumed by a monitoring plugin or forwarded to an external SIEM.

### Guard service — AI-safe interception

`VGuardService` is a three-stage interceptor designed for agentic workflows:

```java
String clean  = guard.sanitizeInput(sessionId, userText);   // PII redaction
String safe   = guard.checkOutput(modelOutput);              // keyword blocking
boolean allow = guard.evaluateToolCall(agentId, "http.get", args); // SSRF check
```

The default implementation only blocks SSRF. Replace it with your own guard to add email/phone/credit card redaction, a custom deny-list, human-approval gates, or anything else — see [example 11](../examples/11-custom-guard/).

### LAN federation without a registry

Node.js has no concept of multi-node topology. VATN nodes announce themselves via UDP multicast on `224.0.0.251:7719` and resolve each other by name:

```java
VDiscovery discovery = ctx.getService(VDiscovery.class).orElseThrow();
VNameResolver resolver = ctx.getService(VNameResolver.class).orElseThrow();

List<String> peers = discovery.getPeers();            // all nodes on LAN
Optional<String> uri = resolver.resolve("node-a1b2"); // name → "http://192.168.1.5:8080"
```

This is v1 — no TLS, no bonding. v2 upgrades it to a full cryptographically verified mesh.

### Beyond Helidon

Helidon 4 SE is an excellent HTTP library, but it is just an HTTP library. VATN adds on top of it:

- **Plugin lifecycle** — PF4J-based load/unload/hot-swap with trust enforcement; Helidon has no plugin model
- **DAG engine** — task graphs with retry, XCom, pools, scheduling, event log; nothing like this in Helidon
- **OIPC transport** — a language-agnostic binary IPC protocol; Helidon only speaks HTTP and gRPC
- **Workspace abstraction** — `ctx.getWorkspacePath()`, `VFileService` — scoped to the node, overridable via `VATN_WORKSPACE`
- **Secrets** — encrypted at rest, mount-path SPI, injected into plugins transparently
- **Structured security model** — trust levels, flow policies, interjectors; Helidon leaves security to you
- **GraalVM C ABI** — `@CEntryPoint` bridge so a C/Rust process can embed a full VATN node via `vatn_node_start(config)`

---

## 4. Prerequisites and setup

| Requirement | Minimum | Notes |
|------------|---------|-------|
| JDK | Java 25 | OpenJDK 25 for JVM builds; GraalVM 25 for native image builds |
| Maven | 3.9+ | `mvn -v` to check |
| GraalVM | 25.0.2+ (Oracle) | Required for `mvn -Pnative package`; includes `native-image` |
| wrk | Optional | For external HTTP benchmarks (`brew install wrk`) |
| SQLite | Bundled | Via `sqlite-jdbc`; no system install needed |

### Install Java 25 (JVM-only builds)

Using [SDKMAN](https://sdkman.io/):
```bash
sdk install java 25-open
sdk use java 25-open
java -version   # → openjdk 25 ...
```

Or download directly from [jdk.java.net/25](https://jdk.java.net/25/).

### Install GraalVM 25 (required for native image)

```bash
sdk install java 25.0.2-graal
sdk use java 25.0.2-graal
native-image --version   # → GraalVM 25.0.2+10 ...
```

Or download from [graalvm.org/downloads](https://www.graalvm.org/downloads/).

---

## 5. Building VATN from source

All three build modes require the same JVM. Install it once:

```bash
sdk install java 25.0.2-graal
sdk use java 25.0.2-graal
```

```bash
git clone <vatn-repo-url>
cd vatn

# Build all modules and install to local Maven repo
mvn clean install -DskipTests

# With tests (takes ~30 s)
mvn clean install

# JVM + Project Leyden AOT cache (see section 20)
mvn package verify -Pleyden -pl vatn-cli -am -DskipTests
# → vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar  (22 MB fat JAR)
# → vatn-cli/target/vatn.aot                   (15 MB Leyden cache)

# Native CLI binary (requires GraalVM 25 — see section 19)
mvn clean package -Pnative -pl vatn-cli -am -DskipTests
# → vatn-cli/target/vatn  (~113 MB self-contained binary)
```

### Module build order

Maven resolves this automatically, but the dependency order is:

```
vatn-bom → vatn-api → vatn-core → vatn-cli
                   → vatn-bench
                   → vatn-test
                   → vatn-verify
                   → vatn-plugin-indexer
                   → vatn-plugin-scraper
                   → examples/*
```

### Running the tests

```bash
# All tests
mvn test

# One module only
mvn test -pl vatn-core

# One test class
mvn test -pl vatn-core -Dtest=VDagEngineTest
```

The test suite currently has 53 tests in `vatn-core`. All should pass green.

### Environment variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `VATN_OTLP_ENDPOINT` | Enable OTLP tracing (e.g. `http://localhost:4317`) | disabled |
| `OTEL_SERVICE_NAME` | Service name in traces | `vatn-node` |
| `VATN_MASTER_KEY` | AES key for secrets encryption | auto-generated at `~/.vatn/.master_key` |
| `VATN_WORKSPACE` | Root path for workspace-scoped files | `System.getProperty("user.dir")` |

---

## 6. Hello World — your first VATN app

Let's build from scratch, step by step. Create a new Maven project:

```xml
<!-- pom.xml -->
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>my-first-vatn-app</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <java.version>25</java.version>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
  </properties>

  <dependencies>
    <!-- The API — zero external deps, what your plugin compiles against -->
    <dependency>
      <groupId>dev.vatn</groupId>
      <artifactId>vatn-api</artifactId>
      <version>1.0-SNAPSHOT</version>
    </dependency>
    <!-- The runtime — Helidon + all platform services -->
    <dependency>
      <groupId>dev.vatn</groupId>
      <artifactId>vatn-core</artifactId>
      <version>1.0-SNAPSHOT</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation=
                  "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.example.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Create the plugin:

```java
// src/main/java/com/example/HelloPlugin.java
package com.example;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

public class HelloPlugin implements VNodePlugin {

    @Override
    public String getId() { return "com.example.hello"; }

    @Override
    public String getName() { return "Hello App"; }

    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.register("/hello", routes -> routes
            .get("/", (req, res) -> res.send("Hello from VATN!"))
            .get("/{name}", (req, res) -> {
                String name = req.pathParam("name");
                res.sendJson("{\"message\":\"Hello, " + name + "!\"}");
            })
        );
    }

    @Override
    public void onShutdown() {}
}
```

Create the entry point:

```java
// src/main/java/com/example/Main.java
package com.example;

import dev.vatn.core.VNodeRunner;

public class Main {
    public static void main(String[] args) throws Exception {
        VNodeRunner runner = VNodeRunner.create(8080);
        runner.addPlugin(new HelloPlugin());
        runner.start();
        // The server runs until Ctrl+C or runner.stop() is called.
    }
}
```

Build and run:

```bash
mvn package -DskipTests
java -jar target/my-first-vatn-app-1.0-SNAPSHOT.jar
```

Test it:

```bash
curl http://localhost:8080/hello
# Hello from VATN!

curl http://localhost:8080/hello/Alice
# {"message":"Hello, Alice!"}

curl http://localhost:8080/vatn/health
# {"status":"UP","nodeId":"node-a1b2c3","uptimeMs":1234}
```

The `/vatn/health` and `/vatn/ready` endpoints are provided automatically — no configuration required. They are ready for Kubernetes liveness/readiness probes.

---

## 7. HTTP services and routing

### Routing

Every `context.register(path, service)` call mounts a `VHttpService` at the given path prefix. Inside the service you get a `VHttpRoutes` builder:

```java
ctx.register("/api/tasks", routes -> routes
    .get("/",          this::listTasks)
    .get("/{id}",      this::getTask)
    .post("/",         this::createTask)
    .put("/{id}",      this::updateTask)
    .delete("/{id}",   this::deleteTask)
);

private void getTask(VHttpRequest req, VHttpResponse res) {
    String id = req.pathParam("id");
    String filter = req.queryParam("filter").orElse("all");
    String body = req.getBody(); // POST/PUT request body
    
    res.status(200).sendJson("{\"id\":\"" + id + "\"}");
}
```

`VHttpRequest` and `VHttpResponse` are VATN abstractions, not Helidon types. This means you can unit-test your handlers without starting an HTTP server — just mock them or use the `vatn-test` harness.

### Reading request data

```java
// Path parameter: GET /tasks/{id}
String id = req.pathParam("id");

// Query parameter: GET /tasks?status=open
String status = req.queryParam("status").orElse("all");

// Body (POST/PUT)
String json = req.getBody();
```

### Writing responses

```java
res.send("plain text");                          // 200, text/plain
res.sendJson("{\"ok\":true}");                   // 200, application/json
res.status(404).send("not found");              // custom status
res.status(201).sendJson("{\"id\":\"xyz\"}");   // 201 Created
```

### Error handling

Throw any unchecked exception — VATN catches it and returns 500. For expected errors, set the status explicitly before calling `send`.

---

## 8. WebSocket and Server-Sent Events

### WebSocket

Register a WebSocket listener directly on the runner (not inside a plugin service):

```java
@Override
public void onInitialize(VNodeContext ctx) {
    // Note: WebSocket registration goes via the runner, accessed before start()
    // In a real app, store a reference or use the runner's registerWebSocket API
}

// In Main.java:
runner.registerWebSocket("/ws/chat", new VWsListener() {
    public void onOpen(VWsSession session) {
        session.send("Welcome!");
    }
    public void onMessage(VWsSession session, String msg) {
        session.send("Echo: " + msg);
    }
    public void onClose(VWsSession session, int code) {}
    public void onError(VWsSession session, Throwable t) {}
});
```

See [example 03](../examples/03-websocket-chat/) for a full multi-user chat room with broadcast.

### Server-Sent Events (SSE)

VATN exposes a built-in SSE bridge at `GET /vatn/ui/stream`. Any plugin can push updates to connected browser clients by publishing to the `vatn.ui.updates` channel:

```java
// Server — publish from anywhere in your plugin
ctx.getMessaging().publish("vatn.ui.updates",
    "{\"sessionId\":\"abc\",\"type\":\"metric\",\"value\":42}".getBytes());
```

```javascript
// Browser
const src = new EventSource('/vatn/ui/stream?sessionId=abc');
src.onmessage = e => {
    const data = JSON.parse(e.data);
    updateDashboard(data);
};
```

Messages are filtered by `sessionId` when provided. See [example 05](../examples/05-realtime-dashboard/) for a live metrics dashboard.

---

## 9. Pub/Sub messaging

`VMessaging` is VATN's event bus. It is always in-process in v1 and will automatically bridge across nodes in v2 via OIPC without API changes.

```java
VMessaging messaging = ctx.getMessaging();

// Subscribe
messaging.subscribe("orders.created", bytes -> {
    String json = new String(bytes);
    processOrder(json);
});

// Publish from anywhere — even from a different plugin
messaging.publish("orders.created", order.toJson().getBytes());
```

Each subscriber callback runs on its own virtual thread, so blocking I/O inside a subscriber is safe. If a subscriber throws, the error is logged and the next subscriber continues.

**Best practices:**
- Use reverse-DNS channel names (`com.example.orders.created`) to avoid collisions across plugins.
- Keep payloads small (JSON or Protobuf bytes). For large data, publish a stream reference and use `VStream`.
- For events that must survive a node restart or reach multiple independent consumers at different paces, use `VTopicService` instead — see section 11.

---

## 10. Named work queues (VQueueService)

> **Inspired by [honker](https://github.com/russellromney/honker)** — the insight that if SQLite is your primary store, the message queue should live in the same file. No dual-write problem, no broker process, no network hop between your business INSERT and your job enqueue.

`VQueueService` provides named, durable, at-least-once work queues backed by the node's SQLite database. Unlike `VJobQueue` (which is a single global queue with registered handler callbacks), named queues expose explicit claim/ack semantics — the worker controls when a job is acknowledged and can implement batching, result storage, and custom retry logic.

Every queue lives in `vatn_named_queue_jobs`. Crashed workers leave claimed jobs with an expired visibility timeout — the background sweeper automatically returns them to `PENDING` so another worker can pick them up.

### Quick start

```java
VQueueService qs = ctx.getService(VQueueService.class).orElseThrow();
VNamedQueue emails = qs.queue("emails");

// Producer
String jobId = emails.enqueue("{\"to\":\"alice@example.com\"}");

// Consumer — background virtual thread, auto-ack on success, auto-nack on exception
emails.consume("worker-1", job -> {
    sendEmail(job.payload());
    // returning normally acks the job; throwing nacks it and schedules a retry
});
```

### Atomic enqueue with a business write

The killer feature: enqueue a job in the same SQLite transaction as your business INSERT — zero chance of inserting an order without the downstream job, or vice versa:

```java
VPersistenceService db = ctx.getService(VPersistenceService.class).orElseThrow();
try (Connection conn = db.getConnection()) {
    conn.setAutoCommit(false);
    try (var ps = conn.prepareStatement("INSERT INTO orders(user_id, total) VALUES (?, ?)")) {
        ps.setInt(1, userId);
        ps.setLong(2, total);
        ps.executeUpdate();
    }
    emails.enqueue("{\"to\":\"alice@example.com\"}", conn);  // same transaction
    conn.commit();
}
```

### Priority, delayed jobs, and DLQ

```java
// Urgent — claimed before normal jobs
emails.enqueue("{\"to\":\"ceo@example.com\"}", 10);

// Run no sooner than 10 minutes from now
emails.enqueueAt("{\"to\":\"bob@example.com\"}", Instant.now().plusSeconds(600));

// Custom options: 10-min visibility timeout, 5 attempts, 60-s backoff, DLQ
VClaimOptions opts = VClaimOptions.defaults()
    .withVisibility(Duration.ofMinutes(10))
    .withMaxAttempts(5)
    .withBackoff(Duration.ofSeconds(60))
    .withDeadLetterQueue("emails.dlq");

VNamedQueue emailsWithRetry = qs.queue("emails", opts);
```

### Manual claim / ack (batch workers)

```java
List<VQueueJob> batch = emails.claimBatch("worker-1", 32);
batch.parallelStream().forEach(job -> {
    try {
        sendEmail(job.payload());
        emails.ack(job.id(), "worker-1");
    } catch (Exception e) {
        emails.nack(job.id(), "worker-1", e.getMessage());
    }
});
```

### Result storage and waiting

```java
// Consumer stores a result when acking
emails.ack(jobId, "worker-1", "{\"sentAt\":\"2026-05-28T12:00:00Z\"}");

// Producer waits for the result (safe on a virtual thread)
Optional<String> result = emails.waitResult(jobId, Duration.ofSeconds(30));
```

### Job states

| State | Meaning |
|-------|---------|
| `PENDING` | Waiting to be claimed |
| `CLAIMED` | Held by a worker; returns to PENDING if visibility timeout expires |
| `DONE` | Successfully acked |
| `FAILED` | Nacked, will retry (attempts < maxAttempts) |
| `DEAD` | Exhausted retries; inspect via `listDeadLetters()` or moved to DLQ |

---

## 11. Durable pub/sub topics (VTopicService)

> **Also inspired by [honker](https://github.com/russellromney/honker)** — per-consumer offset tracking in SQLite means every subscriber has its own cursor and replays missed events after a restart, without an external broker.

`VTopicService` provides durable, append-only event streams where every published event is stored in `vatn_topic_events` and each named consumer tracks its position in `vatn_topic_offsets`. On restart, a consumer replays all events past its last saved offset before transitioning to live delivery.

Use `VMessaging` (section 9) for in-process ephemeral pub/sub where durability is not needed. Use `VTopicService` when:
- Events must survive node restarts
- Multiple independent consumers need to read the same stream at different paces
- You need replay / seek semantics

### Quick start

```java
VTopicService ts = ctx.getService(VTopicService.class).orElseThrow();
VTopic events = ts.topic("user-events");

// Publisher
events.publish("{\"userId\":42,\"action\":\"login\"}");

// Two independent consumers — each has its own offset cursor
events.subscribe("audit-log", event ->
    auditLog.record(event.id(), event.payload()));

events.subscribe("analytics", event ->
    analytics.ingest(event.payload()));
```

### Atomic publish with a business write

```java
Connection conn = db.getConnection();
conn.setAutoCommit(false);
// ... business INSERT ...
events.publish("{\"userId\":42,\"action\":\"registered\"}", conn);  // same transaction
conn.commit();
```

### Replay, seek, and offset management

```java
// Replay the full topic from the beginning for a new consumer
events.seek("new-consumer", 0);

// Resume from a specific event (e.g. after a manual investigation)
events.seek("audit-log", 1234);

// Query current state
long latest   = events.latestOffset();
long myOffset = events.getOffset("audit-log");

// Read events directly (no background thread)
List<VTopicEvent> batch = events.read(myOffset, 100);
```

### Subscription lifecycle

```java
// Stop cleanly and flush the offset to the DB
try (VTopicSubscription sub = events.subscribe("audit-log", handler)) {
    Thread.sleep(60_000);
}  // offset saved on close

// Pause / resume without losing position
sub.pause();
// ... do something else ...
sub.resume();
```

### Housekeeping

```java
// Delete events with id <= 10000 (consumers with saved offset < 10000 will miss them)
events.prune(10_000);
```

---

## 12. Advisory locks (VResourceLockService)

`VResourceLockService` provides TTL-protected advisory locks backed by `vatn_resource_locks` in SQLite. A crashed holder's lock expires automatically — no manual cleanup.

### RAII API (preferred)

```java
VResourceLockService locks = ctx.getService(VResourceLockService.class).orElseThrow();

// Non-blocking — returns empty if already held
locks.tryAcquire("report-generator", Duration.ofMinutes(5)).ifPresent(lock -> {
    try (lock) {
        generateReport();   // lock released when block exits or after TTL on crash
    }
});

// Blocking — waits up to 10 s for the lock to become available
try (VLock lock = locks.acquire("db-migration", Duration.ofMinutes(2), Duration.ofSeconds(10))) {
    runMigration();
}
```

### Renewing a long-running lock

```java
try (VLock lock = locks.acquire("long-job", Duration.ofMinutes(5), Duration.ofSeconds(10))) {
    while (moreWork()) {
        processChunk();
        if (!lock.renew(Duration.ofMinutes(5))) {
            throw new IllegalStateException("Lost lock mid-job");
        }
    }
}
```

VATN also uses `VResourceLockService` internally for DAG scheduler leader election: only the node holding `vatn.scheduler` fires cron DAGs, preventing double-firing in a cluster.

### Low-level API

```java
// Legacy boolean API — kept for backward compatibility
boolean ok = locks.tryLock("backup", 60);   // 60-second TTL
if (ok) {
    try { doBackup(); }
    finally { locks.unlock("backup"); }
}
```

---

## 13. Persisting data

VATN ships with `VPersistenceService` — a JDBC connection pool backed by SQLite by default, swappable to PostgreSQL.

### Schema setup

Register your tables before calling `runner.start()` using a schema contributor:

```java
runner.addSchemaContributor(conn -> {
    conn.createStatement().execute("""
        CREATE TABLE IF NOT EXISTS tasks (
            id      TEXT PRIMARY KEY,
            title   TEXT NOT NULL,
            done    INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL
        )
    """);
});
runner.start();
```

### CRUD in a handler

```java
@Override
public void onInitialize(VNodeContext ctx) {
    VPersistenceService db = ctx.getService(VPersistenceService.class).orElseThrow();
    ctx.register("/tasks", routes -> routes
        .get("/",  (req, res) -> listTasks(db, req, res))
        .post("/", (req, res) -> createTask(db, req, res))
    );
}

private void listTasks(VPersistenceService db, VHttpRequest req, VHttpResponse res) {
    try (Connection conn = db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT id, title, done FROM tasks")) {
        ResultSet rs = ps.executeQuery();
        var list = new ArrayList<String>();
        while (rs.next()) {
            list.add("{\"id\":\"" + rs.getString(1) + "\",\"title\":\"" + rs.getString(2) + "\"}");
        }
        res.sendJson("[" + String.join(",", list) + "]");
    } catch (Exception e) {
        res.status(500).send(e.getMessage());
    }
}
```

See [example 02](../examples/02-rest-api/) for a full task manager with CRUD, pagination, and error handling.

### Isolating your application's database from the VATN framework

By default VATN stores everything in `~/.vatn/database.db`. That is the right default for standalone VATN tools, but **if you are building a product on top of VATN** you should keep your data separate so that:

- Other VATN-based applications on the same machine are not affected by your schema changes.
- Your migrations and the VATN framework migrations cannot collide.
- Your application data (user settings, sessions, secrets) does not leak into the VATN framework store.

Call `withDbPath(Path)` on the runner before `start()`. The parent directory is created automatically.

```java
// ~/.myapp/myapp.db — completely isolated from ~/.vatn/database.db
VNodeRunner.create(8080)
    .withDbPath(Paths.get(System.getProperty("user.home"), ".myapp", "myapp.db"))
    .addPlugin(new MyPlugin())
    .start();
```

**Keep the path decision in one place.** If every CLI command calls `VNodeRunner.create(0)` independently, they will each need the path — and you will eventually drift. The clean pattern is a single static factory method:

```java
public class MyApp {

    /** Canonical DB for this application — never ~/.vatn/database.db. */
    public static final Path DB_PATH =
            Paths.get(System.getProperty("user.home"), ".myapp", "myapp.db");

    /** All entry points call this instead of VNodeRunner.create() directly. */
    public static VNodeRunner createRunner() {
        return VNodeRunner.create(0).withDbPath(DB_PATH);
    }
}
```

```java
// Any command
VNodeRunner runner = MyApp.createRunner();
runner.addPlugin(new MyPlugin());
runner.start();
```

With this pattern, moving the database path later (e.g. to respect `XDG_DATA_HOME` on Linux) is a one-line change.

**Scope of `withDbPath`:** the path is used by `VPersistenceService` and every service that shares the same pool (`VClockService`, `VResourceLockService`, `VQueueService`, `VTopicService`, and any `VSchemaContributor` your plugins register). Node identity (`~/.vatn/identity.pem`) and the master secrets key (`~/.vatn/.master_key`) are separate files — those are controlled by the `identityPath` factory parameter and the `VATN_MASTER_KEY` environment variable respectively.

---

## 14. Background jobs with the DAG engine

The DAG engine is one of VATN's most distinctive features. Think of it as Airflow — but embedded in your process, backed by SQLite instead of Postgres+Redis, and with sub-millisecond task dispatch.

### Core concepts

| Concept | What it is | Analogy |
|---------|-----------|---------|
| `VDag` | Blueprint of a pipeline | An Airflow DAG definition |
| `VDagTask` | One step in the pipeline | An Airflow task |
| `VOperator` | The code that runs a task | An Airflow operator class |
| `VDagRun` | One execution of a DAG | An Airflow DagRun |
| `VXCom` | Data passed between tasks | Airflow XCom |
| `VPool` | Concurrency limit per operator | Airflow Pool |

### A three-stage pipeline

```java
@Override
public void onInitialize(VNodeContext ctx) {
    VDagRegistry registry = ctx.getService(VDagRegistry.class).orElseThrow();
    VDagEngine engine = ctx.getService(VDagEngine.class).orElseThrow();

    // 1. Register operators (what each task type does)
    registry.registerOperator(new VOperator() {
        public String operatorType() { return "extract"; }
        public String execute(VTaskContext tc) throws Exception {
            String url = tc.getConf().getOrDefault("url", "https://api.example.com/data");
            // ... fetch data
            tc.getXCom().pushReturn(tc.getTaskId(), "100 records");
            return "extracted";
        }
    });

    registry.registerOperator(new VOperator() {
        public String operatorType() { return "transform"; }
        public String execute(VTaskContext tc) throws Exception {
            String count = tc.getXCom().pullReturn("extract").orElse("0");
            tc.log("Transforming " + count);
            // ... transform data
            return "transformed";
        }
    });

    registry.registerOperator(new VOperator() {
        public String operatorType() { return "load"; }
        public String execute(VTaskContext tc) throws Exception {
            // ... write to DB
            return "loaded";
        }
    });

    // 2. Define the pipeline (task dependencies form the DAG)
    VDag pipeline = VDag.manual("etl-pipeline", "Daily ETL pipeline",
        Map.of(
            "extract",   VDagTask.of("extract",   "extract",   Set.of(),           Map.of("url", "https://...")),
            "transform", VDagTask.of("transform", "transform", Set.of("extract"),  Map.of()),
            "load",      VDagTask.of("load",      "load",      Set.of("transform"), Map.of())
        )
    );
    registry.register(pipeline);

    // 3. Expose a trigger endpoint
    ctx.register("/pipelines", routes -> routes
        .post("/etl/trigger", (req, res) -> {
            VDagRun run = engine.trigger("etl-pipeline", Map.of(), true);
            res.sendJson("{\"runId\":\"" + run.runId() + "\"}");
        })
    );

    // 4. Resume any runs interrupted by a prior process crash
    registerOperators(registry);
    engine.resumeInterruptedRuns();
}
```

### Scheduled pipelines

```java
// Run at 02:00 every night
VDag nightly = VDag.scheduled("nightly", "Nightly pipeline",
    "0 2 * * *",
    Map.of("run", VDagTask.of("run", "my-operator", Set.of(), Map.of()))
);
registry.register(nightly);
```

`VDagScheduler` wakes up every minute, checks which DAGs are due, and triggers runs automatically.

### Parallel fan-out

```java
// Process 100 items in parallel and wait for all to finish
List<Map<String, String>> inputs = IntStream.range(0, 100)
    .mapToObj(i -> Map.of("partition", String.valueOf(i)))
    .toList();
List<VDagRun> results = engine.fanOut("process-item", inputs);
long failed = results.stream().filter(r -> r.state() == VDagRunState.FAILED).count();
```

See [example 04](../examples/04-dag-etl-pipeline/) for a full ETL pipeline with retry policies, sensors, and pool limits.

---

## 15. Secrets and configuration

### Configuration

```java
VConfiguration config = ctx.getConfiguration();

String host = config.get("DB_HOST").orElse("localhost");
int port    = config.getInt("DB_PORT").orElse(5432);
```

`VConfiguration` reads from environment variables and `vatn.yaml` in the workspace directory. Environment variables take precedence.

### Encrypted secrets

```java
VSecretService secrets = ctx.getService(VSecretService.class).orElseThrow();

// Store a secret (encrypted with AES-256-GCM)
secrets.put("api.key", "sk-very-secret-value");

// Read it back
String key = secrets.get("api.key").orElseThrow();
```

Secrets are stored at `~/.vatn/secrets/` with `rw-------` permissions. The master encryption key lives at `~/.vatn/.master_key` — set `VATN_MASTER_KEY` in production to inject it from a secret manager rather than the filesystem.

---

## 16. Security: guard policies and trust levels

### VGuardService

The guard service intercepts three points in every agentic interaction. The defaults are safe, but you can replace the implementation to add your own rules:

```java
@Override
public void onInitialize(VNodeContext ctx) {
    ctx.registerService(VGuardService.class, new MyGuard());
}

class MyGuard implements VGuardService {

    @Override
    public String sanitizeInput(String sessionId, String text) {
        // Strip PII before sending to a model
        return text.replaceAll("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", "[EMAIL]");
    }

    @Override
    public String checkOutput(String text) {
        // Block profanity or sensitive keywords in model output
        if (text.contains("confidential")) return "[REDACTED]";
        return text;
    }

    @Override
    public boolean evaluateToolCall(String agentId, String toolId, Map<String, String> args) {
        // Block internal IP access via SSRF
        return !args.values().stream().anyMatch(v -> v.contains("169.254."));
    }
}
```

See [example 11](../examples/11-custom-guard/) for a complete implementation with regex-based PII redaction, a keyword deny-list, and a per-tool block list.

### Flow policies and trust levels

When a plugin creates a stream, VATN checks its trust level against the policy's required level:

```java
// This will throw SecurityException if the calling plugin has SANDBOXED trust
OutputStream out = ctx.getStream().createPolicyStream("my-stream",
    VFlowPolicy.of(VFlowMode.TRUSTED_RELAY, VTrustLevel.RESTRICTED));
```

Policy interjectors let you add dynamic approval logic:

```java
((VStreamServiceImpl) ctx.getStream()).addInterjector((pluginId, policy) -> {
    if (blocklist.contains(pluginId)) return VPolicyInterjector.Decision.DENY;
    return VPolicyInterjector.Decision.PASS;
});
```

`DENY` immediately throws and publishes a `PolicyViolation` event to `vatn.monitor.security`.

---

## 17. Node identity and signatures

Every VATN node has an Ed25519 identity. Use it to prove origin and verify authenticity:

```java
VNodeIdentity identity = ctx.getService(VNodeIdentity.class).orElseThrow();

// Sign a payload
byte[] data = "important message".getBytes();
byte[] signature = identity.sign(data);

// Verify — either your own or a known peer's public key
boolean valid = identity.verify(data, signature, identity.getPublicKey());

// Get the node's public key (share this with peers)
PublicKey pubKey = identity.getPublicKey();
```

The keypair is generated on first boot and stored at `~/.vatn/.identity` — on POSIX systems with `rw-------` permissions; on Windows the file is hidden.

---

## 18. Observability: health, tracing, and rate limiting

### Health and readiness endpoints

Always available, no setup required:

```
GET /vatn/health  → {"status":"UP","nodeId":"...","uptimeMs":12345}
GET /vatn/ready   → {"status":"READY"}  (or 503 + {"status":"STARTING"})
```

Wire `/vatn/ready` into your Kubernetes `readinessProbe` and `/vatn/health` into `livenessProbe`.

### Distributed tracing

Set `VATN_OTLP_ENDPOINT` before starting the node and traces are sent to any OpenTelemetry-compatible backend (Jaeger, Tempo, OTLP collector):

```bash
export VATN_OTLP_ENDPOINT=http://localhost:4317
export OTEL_SERVICE_NAME=my-app
java -jar my-app.jar
```

If `VATN_OTLP_ENDPOINT` is not set, `VTracingService` is a zero-cost noop — no allocation, no overhead.

### Rate limiting

```java
VRateLimiter limiter = ctx.getService(VRateLimiter.class).orElse(null);
if (limiter != null) {
    limiter.configure("api.write", 10); // 10 req/s
}

// In your handler:
if (limiter != null && !limiter.tryAcquire("api.write")) {
    res.status(429).send("rate limit exceeded");
    return;
}
```

`tryAcquire` is lock-free per key. Unconfigured keys always pass. `configure(key, 0)` removes the limit.

---

## 19. Running the examples

All examples are self-contained Maven modules under `examples/`. Each has its own `README.md` explaining what it demonstrates.

```bash
# Build all examples (from the vatn/ root)
mvn package -pl examples -am -DskipTests

# Or build and run a single example
cd examples/01-hello-world
mvn package -DskipTests
java -jar target/01-hello-world-*.jar
```

| # | Run | What to try |
|---|-----|------------|
| [01 Hello World](../examples/01-hello-world/) | `java -jar target/...jar` | `curl localhost:8080/hello/world` |
| [02 REST API](../examples/02-rest-api/) | port 8081 | `curl -X POST localhost:8081/tasks -d '{"title":"Buy milk"}'` |
| [03 WebSocket Chat](../examples/03-websocket-chat/) | port 8082 | Open two browser tabs to `localhost:8082` |
| [04 DAG Pipeline](../examples/04-dag-etl-pipeline/) | port 8083 | `curl -X POST localhost:8083/pipelines/etl/trigger` |
| [05 Dashboard](../examples/05-realtime-dashboard/) | port 8084 | Open `localhost:8084` in a browser |
| [06 Scheduled Report](../examples/06-scheduled-report/) | port 8085 | Watch logs for cron trigger |
| [07 Chat App](../examples/07-chat-app/) | port 8086 | Full browser UI, multi-room |
| [11 Custom Guard](../examples/11-custom-guard/) | port 8090 | Logs show PII redaction and SSRF blocking |

---

## 20. Service SPI reference

Access any service via `ctx.getService(Type.class).orElseThrow()` — or via the typed accessors on `VNodeContext` (`ctx.getMessaging()`, `ctx.getStream()`, etc.).

| Service | Accessor | Since | Description |
|---------|----------|-------|-------------|
| `VMessaging` | `ctx.getMessaging()` | 1.0 | In-process pub/sub |
| `VStream` | `ctx.getStream()` | 1.0 | Piped streams and cross-node relay |
| `VJson` | `ctx.getJson()` | 1.0 | Parse, stringify, query JSON |
| `VConfiguration` | `ctx.getConfiguration()` | 1.0 | Env vars and `vatn.yaml` |
| `VMemoryChannel` | `ctx.getMemory()` | 1.0 | Key-value workspace memory |
| `VPluginRegistry` | `ctx.getPluginRegistry()` | 1.0 | Query loaded plugins |
| `VClockService` | `ctx.getClock()` | 1.0 | Logical clock with SQLite persistence |
| `VGuardService` | `ctx.getGuard()` | 1.0 | Input/output/tool-call guard |
| `VDiscovery` | `ctx.getDiscovery()` | 1.0 | LAN peer discovery |
| `VPersistenceService` | `ctx.getService(...)` | 1.0 | JDBC connection pool |
| `VHttpClient` | `ctx.getService(...)` | 1.0 | Outbound HTTP client |
| `VFileService` | `ctx.getService(...)` | 1.0 | Workspace-scoped file operations |
| `VTracingService` | `ctx.getService(...)` | 1.0 | Distributed tracing spans |
| `VResourceLockService` | `ctx.getService(...)` | 1.0 | Advisory locks backed by SQLite; RAII `VLock` handles |
| `VDagEngine` | `ctx.getService(...)` | 1.0 | Trigger, cancel, query DAG runs |
| `VDagRegistry` | `ctx.getService(...)` | 1.0 | Register DAGs and operators |
| `VDagScheduler` | `ctx.getService(...)` | 1.0 | Cron-based scheduling |
| `VJobQueue` | `ctx.getService(...)` | 1.0 | Background jobs with retry, TTL, idempotency |
| `VQueueService` | `ctx.getService(...)` | 1.0-alpha.9 | Named work queues — claim/ack, DLQ, atomic enqueue |
| `VTopicService` | `ctx.getService(...)` | 1.0-alpha.9 | Durable pub/sub topics — per-consumer offsets, replay |
| `VSecretService` | `ctx.getSecrets()` | 1.0 | AES-256-GCM encrypted secret store |
| `VNameResolver` | `ctx.getService(...)` | 1.0 | Resolve VATN node names to URIs |
| `VEventLog` | `ctx.getService(...)` | 1.1 | Append-only DAG event log |
| `VRateLimiter` | `ctx.getService(...)` | 1.1 | Per-key token-bucket rate limiter |

### Registering a custom service

Plugins can publish their own services to other plugins loaded in the same node:

```java
// Producer plugin
ctx.registerService(AnalyticsService.class, new AnalyticsServiceImpl());

// Consumer plugin (loaded after producer)
AnalyticsService analytics = ctx.getService(AnalyticsService.class).orElseThrow();
```

---

## 21. Benchmarks

### Build

```bash
cd vatn/
mvn package -pl vatn-bench -am -DskipTests
```

### JMH micro-benchmarks

```bash
# All benchmarks
java -jar vatn-bench/target/vatn-benchmarks.jar

# DAG engine only
java -jar vatn-bench/target/vatn-benchmarks.jar ".*WorkflowDagBench.*" -wi 3 -i 5 -f 1

# JSON throughput
java -jar vatn-bench/target/vatn-benchmarks.jar ".*JsonBench.*"
```

### External HTTP throughput

```bash
brew install wrk   # one-time install
./vatn-bench/bench/http/run.sh 8080
```

This drives real TCP connections against a live VATN node. Compare the result against:

| Runtime | Req/s |
|---------|-------|
| VATN (Helidon 4 SE, M3) | ~310,000 |
| Spring Boot 3 | ~243,000 |
| Node.js / Express | ~78,000 |
| Python / FastAPI | ~25,000 |

### DAG throughput

```bash
./vatn-bench/bench/workflow/run.sh
```

| Engine | 40-task run time |
|--------|----------------|
| VATN | _run to measure_ |
| Windmill | ~2,400 ms |
| Prefect | ~4,900 ms |
| Apache Airflow | ~56,000 ms |

### Generate a benchmark report

```bash
./vatn-bench/bench/report/generate.sh
# Writes results/VATN-Benchmark-Report-<timestamp>.md
```

---

---

## 22. Native image

One of VATN's core philosophies is that every module must run as a native image without compromise — the same code, the same API, the same startup sequence. The `vatn` CLI binary produced by `mvn -Pnative package` is a self-contained executable with ~10 ms cold start and no JVM dependency.

### Prerequisites

You need **Oracle GraalVM 25** (not plain OpenJDK 25). Via SDKMAN:

```bash
sdk install java 25.0.2-graal
sdk use java 25.0.2-graal
native-image --version
# GraalVM 25.0.2+10.1 Java 25 Oracle GraalVM 25.0.2+10.1
```

### Building the native CLI

```bash
cd vatn/
mvn clean package -Pnative -pl vatn-cli -am -DskipTests
```

This compiles `vatn-cli` plus all upstream modules (`-am`) into a single binary. The result is `vatn-cli/target/vatn`.

Smoke-test:

```bash
./vatn-cli/target/vatn --version
# VATN Runtime 1.0.0

./vatn-cli/target/vatn --help
# Usage: vatn [-hvV] [COMMAND]
#   run, init, registry, logs, info, test, benchmark
```

### What is compiled into the binary

The entire VATN runtime is embedded: Helidon 4 SE HTTP/WebSocket, the DAG engine (SQLite-backed), OIPC transport, Ed25519 identity, AES-256-GCM secret store, UDP LAN discovery, VNativeBridge C ABI entry points, and the full SQLite native library. No external files needed at runtime.

### What changes in native mode: dynamic plugin loading

In JVM mode, `VRegistry` uses PF4J to scan a directory and load plugin JARs at startup. **This is disabled in native image mode.** GraalVM builds operate under a closed-world assumption — no new bytecode can be loaded after the binary is sealed.

`VRegistry` detects this automatically at startup via `ImageInfo.inImageCode()` and swaps in `NativeImagePluginManager`, which is a no-op implementation that logs a notice instead of scanning JARs.

```
[VATN-NATIVE] PF4J dynamic JAR loading disabled.
Use VRegistry.registerPlugin() for compiled-in plugins,
or OUT_OF_PROCESS_BIN / FFI_NATIVE for separately compiled plugin binaries.
```

Your plugin's Java API does not change between modes — only how the plugin reaches the runtime.

---

### Plugin model in native mode

There are two supported paths. They can be combined: use Path A for first-party plugins, Path B for third-party or independently released plugins.

#### Path A — Compiled-in plugin (best performance)

Your plugin JAR is on the classpath at `native-image` build time and is compiled directly into the binary. There is no IPC overhead, no process boundary, and the plugin starts at the same instant as the node.

**Step 1: Add your plugin as a dependency** in `vatn-cli/pom.xml`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>my-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

**Step 2: Register it programmatically** in `RunCommand.java` (or wherever the runner is created), before `runner.start()`:

```java
runner.addPlugin(new com.example.MyPlugin());
```

**Step 3: Add native-image reflection metadata** for any types your plugin serializes with Jackson or accesses reflectively. Create `src/main/resources/META-INF/native-image/<groupId>/<artifactId>/reflect-config.json` inside your plugin module:

```json
[
  { "name": "com.example.MyPlugin",          "allDeclaredMethods": true, "allDeclaredFields": true },
  { "name": "com.example.MyConfig",          "allDeclaredMethods": true, "allDeclaredConstructors": true }
]
```

**Step 4: Build:**

```bash
mvn clean package -Pnative -pl vatn-cli -am -DskipTests
```

Your plugin is now part of the binary. Trust level is `FULL` — the plugin is treated as first-party code.

**When to use this path:** Applications where the plugin set is fixed at ship time — embedded devices, single-purpose service binaries, distribution packages where you control everything.

---

#### Path B — Out-of-process native plugin (independent deployment)

Your plugin runs as a **separate native binary** and communicates with the VATN node over **OIPC v2.12** via a Unix Domain Socket (or TCP). The VATN node never loads the plugin's JARs. The plugin and the node are built and versioned independently.

This path also applies to plugins written in non-Java languages — Python, Rust, Go, C. Those languages already use OIPC because they have no other way to talk to a VATN node.

The OIPC wire protocol is language-neutral: an 18-byte V3 binary header (`0x4F` magic, version, opcode, flags, payload length) followed by a JSON or binary payload. Any process that completes the HELLO handshake is a valid OIPC peer. Full spec: [oipc-protocol.md](oipc-protocol.md).

**Java plugin compiled to its own native binary:**

```bash
# In your plugin module:
mvn clean package -Pnative -pl my-plugin -DskipTests
# → my-plugin/target/my-plugin  (independent binary)
```

**Run alongside the node:**

```bash
./vatn-cli/target/vatn run --port 8080 &
./my-plugin/target/my-plugin               # connects via OIPC UDS
```

Trust level is assigned via the OIPC HELLO handshake, based on the plugin manifest and Ed25519 signature — same rules as JVM mode.

**When to use this path:** Plugins distributed as separate packages, plugins that evolve on their own release schedule, plugins written in languages other than Java, or plugins that need process isolation for security.

---

### Trust levels in native mode

| Origin | Trust level | Notes |
|--------|-------------|-------|
| `runner.addPlugin(new MyPlugin())` (Path A) | `FULL` | Compiled in; first-party code |
| OIPC peer with valid VATN signature (Path B) | `RESTRICTED` | Verified at HELLO handshake |
| OIPC peer with no / invalid signature (Path B) | `SANDBOXED` | Limited API access |

Trust enforcement is identical to JVM mode — the same `VRegistry` trust checks, the same `VFlowPolicy` enforcement at stream boundaries.

---

### Native image configuration files reference

GraalVM's closed-world analysis needs help with reflection, resources, and initialization order. VATN ships with pre-configured metadata files:

| File | Module | Purpose |
|------|--------|---------|
| `META-INF/native-image/dev.vatn/vatn-core/reflect-config.json` | `vatn-core` | All VATN impl types, Jackson data models, PF4J stubs, SQLite JDBC |
| `META-INF/native-image/dev.vatn/vatn-core/resource-config.json` | `vatn-core` | `cpp/vatn.h` (for C ABI), SQLite native libs, Helidon service descriptors |
| `META-INF/native-image/dev.vatn/vatn-core/native-image.properties` | `vatn-core` | SLF4J and SQLite build-time initialization policy |
| `META-INF/native-image/dev.vatn.cli/reflect-config.json` | `vatn-cli` | All CLI command classes (picocli reflective instantiation) |
| `META-INF/native-image/dev.vatn.cli/native-image.properties` | `vatn-cli` | picocli build-time initialization |

The root `pom.xml` enables GraalVM's community reachability metadata repository (`<metadataRepository><enabled>true</enabled></metadataRepository>`), which automatically supplies configs for jackson-databind 2.16.1, postgresql 42.7.2, and flyway-core 10.8.1 without manual configuration.

If you add a dependency that has no community metadata, create its reflection config in your module under `META-INF/native-image/<groupId>/<artifactId>/reflect-config.json`. Running the native-image agent (`-agentlib:native-image-agent`) against your test suite is the fastest way to generate a baseline.

---

### Limitations summary

| Feature | JVM mode | Native mode |
|---------|----------|-------------|
| Dynamic JAR plugin loading (PF4J scan) | Yes | **No** — closed-world |
| Compiled-in plugins (Path A) | N/A | **Yes** |
| Out-of-process OIPC plugins (Path B) | Yes | **Yes** |
| Hot-swap / live plugin reload | Yes (PF4J) | No |
| Java agents / bytecode instrumentation | Yes | No |
| Reflection (configured types) | Yes | Yes |
| SQLite persistence | Yes | Yes (native lib embedded) |
| Helidon HTTP / WebSocket | Yes | Yes |
| DAG engine | Yes | Yes |
| OIPC transport (UDS + TCP) | Yes | Yes |
| C ABI (`vatn_node_start`, `vatn_call`) | Yes | Yes |
| LAN discovery (UDP multicast) | Yes | Yes |

Cold start comparison:

| Runtime | Startup time |
|---------|-------------|
| VATN JVM (warm classpath) | ~200 ms |
| VATN native image | ~10 ms |
| Spring Boot 3 (JVM) | ~4,000–8,000 ms |

---

---

## 23. Project Leyden — JVM AOT cache

Project Leyden (JEP 483, delivered in Java 25) is the JVM's built-in answer to cold-start latency — without GraalVM native image. It works on a standard JVM: you run the application once in "training" mode to record which classes were loaded and how they were linked, then convert that recording into an AOT cache file. Subsequent runs restore the pre-loaded and pre-linked class state from the cache, skipping most of the class loading and linking overhead.

**When to prefer Leyden over native image:**
- You need the full JVM (runtime reflection, dynamic class loading, Java agents)
- Your plugins use PF4J dynamic JAR loading (incompatible with native image)
- You want a faster start than plain JVM without a 1-2 minute native-image build
- Your deployment target has a JVM already (Docker base image, server, dev machine)

### Prerequisites

Requires **Java 25+**. No GraalVM native-image needed. GraalVM 25 works, OpenJDK 25 works.

```bash
sdk use java 25.0.2-graal   # or any Java 25+ via SDKMAN
java -version               # verify
```

> **The cache is JVM-build-specific.** You must use the exact same java binary to build and to run with the cache. Switching JVMs (or upgrading) requires rebuilding the cache.

---

### Option 1 — Maven profile (recommended)

The `leyden` profile automates both training phases after the fat JAR is built. It uses the current SDKMAN java by default; override with `-Dvatn.java=/path/to/java`.

```bash
# Build JAR + Leyden cache in one command
mvn package verify -Pleyden -pl vatn-cli -am -DskipTests
```

Output artifacts:

| File | Size | Purpose |
|------|------|---------|
| `target/vatn-cli-1.0-SNAPSHOT.jar` | ~22 MB | Fat JAR (unchanged) |
| `target/vatn.aot` | ~15 MB | Leyden AOT cache |
| `target/vatn.aotconf` | ~14 MB | Training profile (build artifact only) |

At the end of the build, Maven prints the exact run command:

```
[Leyden] Cache ready. Run: ~/.sdkman/.../bin/java -XX:AOTCache=target/vatn.aot -jar target/vatn-cli-1.0-SNAPSHOT.jar
```

### Option 2 — Shell script

`vatn-cli/leyden-cache.sh` wraps the two-phase workflow and adds a cache compatibility check:

```bash
# Build the cache (uses SDKMAN current java automatically)
./vatn-cli/leyden-cache.sh build vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar

# Verify cache is compatible with the current JVM
./vatn-cli/leyden-cache.sh check vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar

# Run with cache
./vatn-cli/leyden-cache.sh run vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar --help

# Override JVM:
JAVA_CMD=/path/to/java ./vatn-cli/leyden-cache.sh build vatn-cli/target/...jar
```

### Option 3 — Manual

```bash
JAR=vatn-cli/target/vatn-cli-1.0-SNAPSHOT.jar
JAVA=~/.sdkman/candidates/java/current/bin/java

# Phase 1: record
$JAVA -XX:AOTMode=record \
      -XX:AOTConfiguration=vatn.aotconf \
      -jar $JAR --version

# Phase 2: create cache
$JAVA -XX:AOTMode=create \
      -XX:AOTConfiguration=vatn.aotconf \
      -XX:AOTCache=vatn.aot \
      -jar $JAR --version

# Phase 3: use
$JAVA -XX:AOTCache=vatn.aot -jar $JAR <args>
```

---

### Measured startup times (Apple M5, GraalVM 25.0.2, 5-run average)

| Runtime | Cold start | Notes |
|---------|-----------|-------|
| **JVM plain** | **~144 ms** | `java -jar vatn-cli.jar` |
| **JVM + Leyden AOT** | **~116 ms** | `java -XX:AOTCache=vatn.aot -jar vatn-cli.jar` — **~20% faster** |
| **GraalVM native image** | **~24 ms** | `./vatn` — **~6× faster than plain JVM** |

The Leyden improvement is modest (~20%) because the VATN CLI's initialization path is dominated by picocli command parsing rather than deep class hierarchies. Applications that load many framework classes (Spring, Hibernate, Helidon server startup) typically see 30–50% improvement with Leyden.

For server workloads where the node runs for hours, the 20–120 ms startup difference rarely matters. The relevant choice is then: **Leyden for full JVM dynamism, native image for edge/embedded/CLI distribution.**

---

### What the Leyden AOT flags do

| Flag | Purpose |
|------|---------|
| `-XX:AOTMode=record` | Training run: records class loading, linking, and initialization order |
| `-XX:AOTConfiguration=<file>` | Path to the recorded profile (input for `create`, ~14 MB) |
| `-XX:AOTMode=create` | Builds the binary cache from the recorded profile |
| `-XX:AOTCache=<file>` | Path to the compiled cache (input at runtime, ~15 MB) |
| `-XX:+AOTClassLinking` | Optional: also pre-links class relationships (no measurable benefit for VATN CLI) |

---

### Distribution

Ship the fat JAR and the `.aot` cache file together. The cache must match the target JVM. In Docker, pin the base image (`FROM eclipse-temurin:25.0.2`) and build the cache in the same image layer:

```dockerfile
FROM eclipse-temurin:25.0.2
COPY target/vatn-cli-1.0-SNAPSHOT.jar /app/
RUN java -XX:AOTMode=record -XX:AOTConfiguration=/app/vatn.aotconf -jar /app/vatn-cli-1.0-SNAPSHOT.jar --version && \
    java -XX:AOTMode=create -XX:AOTConfiguration=/app/vatn.aotconf -XX:AOTCache=/app/vatn.aot -jar /app/vatn-cli-1.0-SNAPSHOT.jar --version
CMD ["java", "-XX:AOTCache=/app/vatn.aot", "-jar", "/app/vatn-cli-1.0-SNAPSHOT.jar"]
```

---

*For the full SPI Javadoc, browse `vatn-api/src/main/java/dev/vatn/api/`. For the OIPC wire protocol, see [oipc-protocol.md](oipc-protocol.md). For the full VATN architecture and dev workflow, see [vatn-architecture.md](vatn-architecture.md).*
