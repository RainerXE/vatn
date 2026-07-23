# VATN Plugins

Drop-in plugins for the [VATN runtime](https://github.com/RainerXE/vatn).

Each plugin implements `VNodePlugin` and is registered with `VNodeRunner.addPlugin()` before calling `.start()`. Plugins declare their dependencies by consuming services from `VNodeContext` — no direct coupling between plugins.

---

## Plugin Catalog

### Infrastructure

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-postgres](vatn-plugin-postgres/) | `vatn-plugin-postgres` | PostgreSQL connection pool via HikariCP. Registers `DataSourceService` and a `postgres` health check. |
| [vatn-plugin-redis](vatn-plugin-redis/) | `vatn-plugin-redis` | Redis client via Jedis. Registers `RedisService` with get/set/del/expire/pub-sub. |
| [vatn-plugin-mongodb](vatn-plugin-mongodb/) | `vatn-plugin-mongodb` | MongoDB sync driver. Registers `MongoService` giving access to collections and the `MongoDatabase`. |
| [vatn-plugin-s3](vatn-plugin-s3/) | `vatn-plugin-s3` | S3-compatible object storage via AWS SDK v2. Supports AWS S3, MinIO, Cloudflare R2, DigitalOcean Spaces. Registers `S3Service` (presign, streaming) **and** the standard `VBlobStore` SPI (content-addressing, range reads, dedup) — so backend-agnostic code works unchanged. |
| [vatn-plugin-email](vatn-plugin-email/) | `vatn-plugin-email` | SMTP email via Jakarta Mail (Angus). Registers `EmailService` with plain-text and HTML send. Supports STARTTLS, SSL, and app passwords. |
| [vatn-plugin-metrics](vatn-plugin-metrics/) | `vatn-plugin-metrics` | Prometheus metrics via Micrometer. Registers `MetricsService` (MeterRegistry) and exposes `GET /metrics` in Prometheus text format. Optional JVM metrics (GC, memory, threads, CPU). |

### Security & Auth

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-auth](vatn-plugin-auth/) | `vatn-plugin-auth` | JWT authentication. Registers `AuthService` and three endpoints: `POST /auth/login`, `POST /auth/refresh`, `GET /auth/me`. Supply a credential validator and secret key. |
| [vatn-plugin-bcrypt](vatn-plugin-bcrypt/) | `vatn-plugin-bcrypt` | BCrypt password hashing. Registers `BcryptService` with hash/verify. Configurable cost factor (default 12 ≈ 250 ms). Pairs with `vatn-plugin-auth`. |
| [vatn-plugin-cors](vatn-plugin-cors/) | `vatn-plugin-cors` | CORS filter (order 150). Adds `Access-Control-*` headers and handles OPTIONS preflight. Permissive defaults or explicit origin allowlist. |
| [vatn-plugin-security](vatn-plugin-security/) | `vatn-plugin-security` | HTTP security headers filter. Injects `X-Frame-Options`, `X-Content-Type-Options`, `Content-Security-Policy`, `HSTS`, and `Referrer-Policy`. Configurable CSP and HSTS values. |

### API & UI

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-swagger](vatn-plugin-swagger/) | `vatn-plugin-swagger` | Swagger / OpenAPI UI. Serves `GET /api-docs` (OpenAPI 3.0 JSON) and `GET /docs` (Swagger UI via CDN). Accepts a pre-built spec or generates a minimal skeleton from config metadata. |

### AI & Data

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-openai](vatn-plugin-openai/) | `vatn-plugin-openai` | LLM client for any OpenAI-compatible API. Registers `LlmService` with `complete()` and `chat()`. Supports OpenAI, Anthropic/Claude, Ollama, and any compatible endpoint. |
| [vatn-plugin-fts](vatn-plugin-fts/) | `vatn-plugin-fts` | **Real full-text search** backed by SQLite FTS5 (BM25 ranking, snippets, prefix/phrase/boolean queries). Registers `FtsService` — no extra infrastructure, uses the node's existing database. See below. |
| [vatn-plugin-scraper](vatn-plugin-scraper/) | `vatn-plugin-scraper` | HTML scraper backed by Jsoup. Fetches a list of URLs, extracts structured entries, and pipes results as NDJSON to a downstream VATN node via `VStream`. |
| [vatn-plugin-indexer](vatn-plugin-indexer/) | `vatn-plugin-indexer` | Stream processor that receives a JSON stream, sorts entries by title, and relays them downstream. Designed as a pipeline stage between scraper and storage nodes. |

### Communication

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-slack](vatn-plugin-slack/) | `vatn-plugin-slack` | Slack notifications via Incoming Webhook. Registers `SlackService` with `notify(message)`. Stateless — no persistent connection. |
| [vatn-plugin-comm](vatn-plugin-comm/) | `vatn-plugin-comm` | **Messaging sidecar hub** for Telegram, Signal, and RCS. Each channel runs as a `VAgent` with optional active-passive failover. Unified `CommService` API for send/receive across all channels. See below. |
| [vatn-plugin-terminalphone](vatn-plugin-terminalphone/) | `vatn-plugin-terminalphone` | **Anonymous E2E-encrypted voice and text over Tor.** Record-and-send voice clips, encrypted text messaging, and a zero-knowledge group relay — all routed through `.onion` addresses. Inspired by [TerminalPhone](https://gitlab.com/here_forawhile/terminalphone). See below. |

### Protocols

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-activitypub](vatn-plugin-activitypub/) | `vatn-plugin-activitypub` | ActivityPub federation. Exposes `/.well-known/webfinger`, `/ap/actor`, `/ap/inbox`, `/ap/outbox`. Handles Follow/Undo activities and sends HTTP-signed Accept responses. RSA-2048 key management built in. |

### Runtime Extensions

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-wasm](vatn-plugin-wasm/) | `vatn-plugin-wasm` | **Sandboxed WASM execution.** Registers `VWasmRuntime` so any plugin can load and call `.wasm` modules. Engine: [Chicory](https://github.com/dylibso/chicory) (pure Java, zero JNI). Supports WASI p1 for Rust/C/Go/Zig binaries with capability-scoped filesystem. Clean swap point for GraalWASM. See below. |
| [vatn-plugin-python](vatn-plugin-python/) | `vatn-plugin-python` | **Sandboxed Python runtime.** Pinokio-compatible `run[]` script format. Manages venvs (uv/pip/conda), supervises Python daemon processes with auto-restart, streams logs. Admin UI at `/python/ui`. See below. |
| [vatn-plugin-node](vatn-plugin-node/) | `vatn-plugin-node` | **Sandboxed Node.js runtime.** Same `run[]` script format as Python plugin. npm/npx package management, supervised Node.js processes with auto-restart, log streaming. Admin UI at `/node/ui`. See below. |

---

## Quick Start — first plugin in 5 minutes

With VATN installed (`vatn init my-project` or `mvn install -DskipTests` from source):

```bash
vatn init my-project      # scaffold Maven project + HelloPlugin skeleton
cd my-project
```

Edit `src/main/java/.../HelloPlugin.java`:

```java
public class HelloPlugin implements VNodePlugin {
    public String getId()      { return "com.example.hello"; }
    public String getName()    { return "Hello VATN"; }
    public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.register("/hello", routes -> routes
            .get("/",       (req, res) -> res.send("Hello from VATN!"))
            .get("/{name}", (req, res) ->
                res.sendJson("{\"msg\":\"Hello, " + req.getPathParam("name") + "!\"}")));
    }

    @Override public void onShutdown() {}
}
```

```bash
vatn run                  # compiles and starts on :8080

curl http://localhost:8080/hello/world
# {"msg":"Hello, world!"}
```

Full step-by-step walkthrough with Node.js analogies, DAG workflows, security, and deployment: **[docs/dev-guide.md](https://github.com/RainerXE/vatn/blob/main/docs/dev-guide.md)**

---

## Dependency setup

### 1. Install VATN to your local Maven repo

```sh
cd /path/to/vatn && mvn install -DskipTests
```

### 2. Add the parent BOM (optional but recommended)

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.vatn.plugins</groupId>
      <artifactId>vatn-plugins-parent</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### 3. Add the plugins you need

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-postgres</artifactId>
</dependency>
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-auth</artifactId>
</dependency>
<!-- add more as needed -->
```

### 4. Register with the node runner

```java
VNodeRunner.create(8080)
    .addPlugin(new SecurityPlugin())
    .addPlugin(new CorsPlugin())
    .addPlugin(new PostgresPlugin(PostgresConfig.of("localhost", 5432, "mydb", "user", "pass")))
    .addPlugin(new AuthPlugin(AuthConfig.of(jwtSecret, myCredentialValidator)))
    .addPlugin(new MetricsPlugin())
    .addPlugin(new SwaggerPlugin(SwaggerConfig.of("My API", "1.0.0")))
    .addPlugin(new MyAppPlugin())
    .start();
```

Plugin load order follows registration order. Plugins that depend on a service registered by another plugin should be added after it.

---

## Plugin detail: vatn-plugin-fts

Real full-text search without standing up Elasticsearch or Meilisearch. `vatn-plugin-fts` creates a single SQLite FTS5 virtual table in the node's existing database and exposes `FtsService`. FTS5 uses `porter unicode61` tokenisation (English stemming + Unicode normalisation) and BM25 relevance ranking with configurable column weights.

```java
VNodeRunner.create(8080)
    .addPlugin(new FtsPlugin())
    .addPlugin(new MyPlugin())
    .start();
```

In your plugin:

```java
FtsService fts = ctx.getService(FtsService.class).orElseThrow();

// Index documents — title is weighted 10× over body
fts.index("books", "book-42", "The Left Hand of Darkness", "Ursula K. Le Guin — science fiction…");
fts.index("books", "book-99", "The Dispossessed", "Anarchist utopian novel by Le Guin…");

// FTS5 query syntax: terms, "exact phrases", prefix*, AND / OR / NOT, column:term
List<FtsResult> hits = fts.search("ursula le guin", 10);
List<FtsResult> inCol = fts.search("books", "title:darkness OR title:dispossessed", 10);

// Each result carries docId, collection, score (higher = better), and a highlighted snippet
hits.forEach(r -> System.out.printf("[%.2f] %s — %s%n", r.score(), r.docId(), r.snippet()));

// Re-index (same docId replaces the previous entry) and delete
fts.index("books", "book-42", updatedTitle, updatedBody);
fts.delete("books", "book-42");

// Batch-clear a collection
fts.clear("drafts");

// Count indexed documents
long total = fts.count("books");
```

**Notes:**
- Requires that the SQLite JDBC driver includes FTS5 (the standard `sqlite-jdbc` artifact does). If `CREATE VIRTUAL TABLE … USING fts5` throws, verify your SQLite build.
- Malformed FTS5 query syntax (e.g. unbalanced quotes) returns an empty result list rather than propagating an exception — safe for user-supplied queries.
- For cross-node search, index documents on each node and use `VRpcService` to fan out the query and merge results.

---

## Plugin detail: vatn-plugin-s3 — VBlobStore SPI

In addition to the S3-specific `S3Service` (presign, streaming), `vatn-plugin-s3` now implements the standard `VBlobStore` SPI, making it a drop-in remote backend for any code that uses content-addressed or keyed blob storage:

```java
// Backend-agnostic code — works with the default local store OR vatn-plugin-s3
VBlobStore blobs = ctx.getService(VBlobStore.class).orElseThrow();

// Content-addressed write — deduplicates by SHA-256
String hash = blobs.putContent(imageStream, "image/jpeg");   // → "sha256:ab12…"

// Named write
blobs.put("covers/42.jpg", jpegBytes, "image/jpeg");

// Range read (HTTP range semantics)
try (InputStream header = blobs.openRange("covers/42.jpg", 0, 64 * 1024)) { … }

// List and delete
blobs.list("covers/").forEach(System.out::println);
blobs.delete("covers/old.jpg");
```

With `vatn-plugin-s3` loaded, `ctx.getService(VBlobStore.class)` returns the S3 implementation. Without it, it returns the runtime's local content-addressed cache (`~/.vatn/blobs`). No code change required when switching backends.

**S3 key mapping:** named keys pass through unchanged; content-addressed keys (`sha256:<hex>`) are stored under `sha256/<hex>` in the bucket. Pin/evict are no-ops on S3 (use bucket lifecycle rules instead).

---

## Plugin detail: vatn-plugin-comm

The communication sidecar manages external messaging channels as `VAgent` instances — long-running background components with built-in active-passive failover.

```java
VNodeRunner.create(8080)
    .addPlugin(new CommPlugin(CommConfig.create()

        // Telegram: long-polling, active-passive failover
        .withTelegram(TelegramConfig.polling(System.getenv("TELEGRAM_TOKEN"))
            .withAgentMode(VAgentMode.activePassive().withFailoverTimeout(10_000)))

        // Signal: via signal-cli-rest-api sidecar
        .withSignal(SignalConfig.of("http://signal-api:8080", System.getenv("SIGNAL_NUMBER"))
            .withAgentMode(VAgentMode.activePassive()))

        // RCS: Twilio provider, webhook inbound
        .withRcs(RcsConfig.twilio(System.getenv("TWILIO_FROM"),
            System.getenv("TWILIO_SID"), System.getenv("TWILIO_TOKEN")))
    ))
    .addPlugin(new MyBotPlugin())
    .start();
```

In your plugin, get `CommService` from context:

```java
CommService comm = ctx.getService(CommService.class).orElseThrow();

// Receive from all channels
comm.onMessage(msg ->
    comm.send(OutboundMessage.replyTo(msg, "Echo: " + msg.text())));

// Send explicitly
comm.send(OutboundMessage.text(CommChannel.TELEGRAM, chatId, "Alert: pipeline failed"));
```

See [vatn-plugin-comm/README.md](vatn-plugin-comm/README.md) for full documentation including Signal sidecar setup, RCS provider config, and the twin pattern for load-balanced webhook receivers.

---

## Plugin detail: vatn-plugin-terminalphone

A VATN-native port of [TerminalPhone](https://gitlab.com/here_forawhile/terminalphone) by *here_forawhile* — a self-contained Bash walkie-talkie that runs entirely over Tor. The plugin replicates the same security model on the JVM: record a complete voice clip, encrypt it with AES-256-CBC and sign it with HMAC-SHA256, then deliver it through the Tor SOCKS5 proxy to a peer's `.onion` address. No accounts, no servers, no cleartext.

```java
VNodeRunner.create(8080)
    .addPlugin(new TerminalPhonePlugin(
        TerminalPhoneConfig.builder("exchange-this-secret-out-of-band")
            .cipher("AES-256-CBC")
            .torPort(9050)
            .listenPort(54321)
            .hmacSigning(true)
            .build()
    ))
    .addPlugin(new MyPlugin())
    .start();
```

In your plugin, get `TerminalPhoneService` from context:

```java
TerminalPhoneService phone = ctx.getService(TerminalPhoneService.class).orElseThrow();

// Share your address with the peer (scan the QR out-of-band)
System.out.println(phone.getQrCode());

// Wire up handlers
phone.onCallConnected(peer -> log.info("Connected to {}", peer));
phone.onTextMessage(msg   -> log.info(">> {}", msg));
phone.onVoiceMessage(pcm  -> phone.playVoice(pcm));

// Dial and talk
phone.call("abc123.onion");
byte[] clip = phone.recordVoice(3_000);  // 3s push-to-talk
phone.sendVoice(clip);
phone.sendText("On my way.");
```

Group calls are supported via **relay mode** — a second listener forwards encrypted frames between callers without decrypting them (zero-knowledge relay). Enable with `.relayMode(true)`.

See [vatn-plugin-terminalphone/README.md](vatn-plugin-terminalphone/README.md) for the full security model, configuration reference, and HTTP endpoint documentation.

---

## Plugin detail: vatn-plugin-wasm

Sandboxed WebAssembly execution using [Chicory](https://github.com/dylibso/chicory) — a pure-Java, zero-JNI WASM runtime. Registers `VWasmRuntime` in the node context so any plugin can load and invoke `.wasm` modules without importing the plugin as a compile-time dependency.

```java
VNodeRunner.create(8080)
    .addPlugin(new WasmPlugin())          // default: auto-loads .vatn/wasm/*.wasm
    .addPlugin(new MyPlugin())
    .start();
```

In your plugin:

```java
VWasmRuntime wasm = ctx.getService(VWasmRuntime.class).orElseThrow();

// Load from bytes (or use auto-loaded module by name)
VWasmModule mod = wasm.load("verifier", Files.readAllBytes(wasmPath));

// Call an exported integer function
long[] result = mod.call("add", 40L, 2L);   // → [42]

// Run a WASI binary (Rust/C/Go/Zig) with scoped filesystem + stdout capture
String output = mod.callWasi(new String[]{"check", "src/main.odin"}, null);
```

**Sandbox model**: WASM linear-memory isolation (Chicory) + WASI capability grants (filesystem scoped to workspace, no network) + `VSubprocessAuditService` (every call logged).

**Upgrading Chicory**: one property in `vatn-plugins-parent/pom.xml` — `<chicory.version>`. Change it and rebuild.

**Switching to GraalWASM**: replace `new ChicoryWasmRuntime(...)` in `WasmPlugin` with a `GraalWasmRuntime` that implements the same `VWasmRuntime` SPI. All callers continue working unchanged. GraalWASM compiles WASM to native machine code on GraalVM JDK — peak performance when you need it.

See [vatn-plugin-wasm/README.md](vatn-plugin-wasm/README.md) for configuration, REST API, language examples (Rust/C/Go/Zig), and the GraalWASM migration path.

---

## Plugin detail: vatn-plugin-python

**Pinokio-compatible Python runtime** — venv/conda management, supervised daemons, and a live admin UI.

```java
VNodeRunner.create(8080).addPlugin(new PythonPlugin()).start();
// → [PYTHON] Found: python3 → Python 3.12.4
// → [PYTHON] uv found: uv 0.4.1
```

Drop any [Pinokio](https://github.com/pinokiocomputer/pinokio)-format app into `.vatn/python/apps/<name>/pinokio.json` and run it:

```bash
curl -X POST http://localhost:8080/python/apps/myapp/run
# → {"status":"completed","stepsRun":3,"daemonProcessIds":["myapp-a1b2c3d4"]}
```

**The `run[]` script format:**

```json
{
  "run": [
    { "method": "shell.run", "params": { "message": "uv pip install fastapi uvicorn",
                                          "venv": "myapp" } },
    { "method": "shell.run", "params": { "message": "uvicorn main:app --port 8001",
                                          "venv": "myapp", "daemon": true, "autoRestart": true } }
  ]
}
```

**What's supported:** `shell.run` (with `venv`, `conda`, `env`, `daemon`, `autoRestart`), `fs.write`, `fs.read`, `local.set/get`, `script.return`, template interpolation (`{{id}}`).

**Package installer priority:** `uv pip install` → `pip install` → `conda install`

**Admin UI:** `GET /python/ui` — process table, log streaming, venv management, one-click start/stop.

**REST endpoints:** `/python/status` · `/python/envs` · `/python/apps` · `/python/apps/{name}/run` · `/python/processes/{id}/status` · `/python/processes/{id}/stop`

See **[vatn-plugin-python/README.md](vatn-plugin-python/README.md)** for full script reference, curl examples, real-world scenarios (vllm, Gradio, Automatic1111), security model, and troubleshooting.

---

## Plugin detail: vatn-plugin-node

**Supervised Node.js runtime** — same `run[]` format as the Python plugin, npm/npx integration, and a live admin UI with Restart button.

```java
VNodeRunner.create(8080).addPlugin(new NodePlugin()).start();
// → [NODE] Found: node → v22.4.0
// → [NODE] npm: npm → 10.8.1
```

Drop a `vatn-node.json` into `.vatn/node/apps/<name>/` and run it:

```bash
curl -X POST http://localhost:8080/node/apps/api-server/run
# → {"status":"completed","stepsRun":3,"daemonProcessIds":["api-server-b3c4d5e6"]}
```

**The `run[]` script format:**

```json
{
  "run": [
    { "method": "npm.install",  "params": { "packages": ["express", "dotenv"] } },
    { "method": "fs.write",     "params": { "path": ".env", "data": "PORT=3001\n" } },
    { "method": "shell.run",    "params": { "message": "node server.js",
                                             "daemon": true, "autoRestart": true,
                                             "env": { "PORT": "3001" } } }
  ]
}
```

**What's supported:** `shell.run` (daemon/autoRestart), `npm.install`, `npx.run`, `fs.write/read`, `local.set/get`, `script.return`. Auto-detects `node`, `npm`, `npx`.

**Admin UI:** `GET /node/ui` — process table with Restart button, log streaming, npm install controls.

**REST endpoints:** `/node/status` · `/node/apps` · `/node/apps/{name}/install` · `/node/apps/{name}/run` · `/node/processes/{id}/status` · `/node/processes/{id}/stop` · `/node/processes/{id}/restart`

See **[vatn-plugin-node/README.md](vatn-plugin-node/README.md)** for full reference, curl examples, real-world scenarios (Express, Next.js, workers), PM2 comparison, and troubleshooting.

---

## Recommended stacks

### Web API

```java
SecurityPlugin  →  CorsPlugin  →  AuthPlugin  →  PostgresPlugin  →  MetricsPlugin  →  SwaggerPlugin  →  YourPlugin
```

### AI assistant with notifications

```java
PostgresPlugin  →  RedisPlugin  →  OpenAiPlugin  →  CommPlugin  →  YourBotPlugin
```

### Data pipeline with search

```java
ScraperPlugin  →  FtsPlugin  →  S3Plugin  →  MetricsPlugin
// ScraperPlugin fetches pages and pushes to VTopic
// FtsPlugin indexes titles/bodies for instant BM25 search
// S3Plugin stores full blobs via VBlobStore (content-addressed, range reads)
```

### Federated social node

```java
SecurityPlugin  →  PostgresPlugin  →  ActivityPubPlugin  →  CommPlugin  →  YourPlugin
```

### Agentic tool runtime (native code in the JVM)

```java
WasmPlugin  →  PostgresPlugin  →  MetricsPlugin  →  YourAgentPlugin
// YourAgentPlugin loads .wasm verifiers, policy engines, or domain tools via VWasmRuntime
```

### AI model serving (Python + Node.js sidecar)

```java
PythonPlugin  →  NodePlugin  →  OpenAiPlugin  →  YourAiPlugin
// PythonPlugin runs ML inference servers (FastAPI, Gradio, vllm)
// NodePlugin runs the frontend / streaming relay
// Both supervised, auto-restarted, and audited by VATN
```

---

## Building

```sh
# Requires vatn-api in local Maven repo (see step 1 above)
mvn install -DskipTests
```
