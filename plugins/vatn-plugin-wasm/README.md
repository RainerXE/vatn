# vatn-plugin-wasm
![Stability](https://img.shields.io/badge/stability-stable-green)

Sandboxed WebAssembly execution for VATN nodes — run native code compiled from Rust, C, Go, Zig, or any WASI-compatible language inside the JVM with capability-based security and per-call audit logging.

**Runtime**: VATN 1.0-alpha.14+

## Backend options

All three backends implement the same `VWasmRuntime` SPI. Switch with a Maven profile — no code changes in callers.

| Backend | Activate | Who it is | Performance | Requires |
|---------|----------|-----------|-------------|---------|
| **Chicory** _(default)_ | `-P chicory` or nothing | [Dylibso](https://github.com/dylibso/chicory) — proven, Maven Central | 8–25 M calls/s (interpreter) | Any Java 25+ JDK |
| **Endive** | `-P endive` | [Bytecode Alliance fork of Chicory](https://github.com/bytecodealliance/endive) — same API, Cranelift compiler on roadmap | Same as Chicory today; 150–500 M/s when Redline ships | Any Java 25+ JDK |
| **GraalWASM** | `-P graalwasm` | [Oracle GraalVM](https://www.graalvm.org/webassembly/) — JIT compiles WASM to native | 200–500 M calls/s after warmup | GraalVM JDK 25+ |

> **Upgrading Chicory**: change `<chicory.version>` in `vatn-plugins-parent/pom.xml`. One property, rebuild.
>
> **Switching to Endive**: `mvn package -P endive`. Endive preserves Chicory's package names — same code compiles against both JARs.
>
> **Switching to GraalWASM**: add a `GraalWasmRuntime` class implementing `VWasmRuntime`, register it in `WasmPlugin.onInitialize`. All callers unchanged.

---

## What It Does

- Registers `VWasmRuntime` in the VATN node context — any plugin can load and call `.wasm` modules without importing this plugin as a compile-time dependency
- Auto-loads `.wasm` files from `.vatn/wasm/` at startup (configurable)
- WASI p1 support: filesystem access scoped to the node workspace, configurable env var forwarding
- Each WASM invocation is recorded in `VSubprocessAuditService` (installed automatically by VATN core)
- REST management API at `/wasm` for dynamic load/invoke/unload
- Designed as a clean swap point for GraalWASM (see [Switching to GraalWASM](#switching-to-graalwasm))

---

## Quick Start

```java
VNodeRunner.create(8080)
    .addPlugin(new WasmPlugin())
    .addPlugin(new MyPlugin())
    .start();
```

Drop `.wasm` files into `.vatn/wasm/` — they are loaded automatically at startup.

### Using the runtime from another plugin

```java
public class MyPlugin implements VNodePlugin {
    @Override
    public void onInitialize(VNodeContext ctx) {

        VWasmRuntime wasm = ctx.getService(VWasmRuntime.class).orElseThrow();

        // ── Pure function call ─────────────────────────────────────────────
        // Module compiled from: rustc --target wasm32-unknown-unknown
        byte[] bytes = Files.readAllBytes(
            ctx.getWorkspacePath().resolve(".vatn/wasm/math.wasm"));
        VWasmModule math = wasm.load("math", bytes);

        long[] result = math.call("add", 40L, 2L);   // → [42]
        long[] fib    = math.call("fibonacci", 10L); // → [55]

        // ── WASI module (stdout capture, scoped filesystem) ───────────────
        // Module compiled from: cargo build --target wasm32-wasi
        VWasmModule verifier = wasm.get("odin-verifier").orElseThrow();
        String output = verifier.callWasi(
            new String[]{"odin", "check", "."},
            null   // env — null uses plugin's allowedEnvVars config
        );
        // output = "Checking 'src/main.odin'... OK\n..."

        // ── Introspection ─────────────────────────────────────────────────
        System.out.println(math.exports()); // [add, fibonacci, mul]
        System.out.println(wasm.runtimeName());    // chicory
        System.out.println(wasm.runtimeVersion()); // 1.7.5
    }
}
```

---

## Configuration

```java
VNodeRunner.create(8080)
    .addPlugin(new WasmPlugin(
        WasmConfig.builder()
            .moduleDir(Paths.get(".vatn/wasm"))    // where to look for .wasm files
            .autoLoadDir(true)                      // load all .wasm on startup
            .readOnlyFs(true)                       // WASI: workspace is read-only
            .maxLinearMemoryPages(256)              // 256 × 64 KiB = 16 MiB cap
            .allowedEnvVars(List.of("PATH", "HOME", "LANG", "JAVA_HOME"))
            .build()
    ))
    .start();
```

| Option | Default | Description |
|--------|---------|-------------|
| `moduleDir` | `.vatn/wasm` | Directory scanned at startup for `.wasm` files |
| `autoLoadDir` | `true` | Load all `.wasm` files from `moduleDir` at startup |
| `readOnlyFs` | `true` | WASI filesystem grants are read-only |
| `maxLinearMemoryPages` | `0` (unlimited) | Cap on WASM linear memory (1 page = 64 KiB) |
| `allowedEnvVars` | `PATH, HOME, LANG` | Host env vars forwarded to WASI processes |

---

## REST API

All endpoints are mounted at `/wasm`.

### `GET /wasm/modules`

List all loaded modules and engine info.

```json
{"runtime":"chicory","version":"1.7.5","modules":["math","odin-verifier"],"count":2}
```

---

### `GET /wasm/modules/{id}/exports`

List exported functions of a loaded module.

```json
{"id":"math","exports":["add","fibonacci","mul","is_prime"]}
```

---

### `POST /wasm/modules/{id}/load`

Dynamically load a module from base64-encoded `.wasm` bytes.

**Request:**
```json
{"bytes":"AGFzbQEAAAA..."}
```

**Response `201`:**
```json
{"id":"math","exports":["add","fibonacci"],"status":"loaded"}
```

---

### `DELETE /wasm/modules/{id}`

Unload a module and release its resources.

```json
{"status":"unloaded","id":"math"}
```

---

### `POST /wasm/modules/{id}/call/{fn}`

Call an exported integer function.

**Request** (`{"args":[40,2]}`)  
**Response** (`{"results":[42]}`)

```bash
curl -X POST http://localhost:8080/wasm/modules/math/call/add \
  -H "Content-Type: application/json" \
  -d '{"args":[40,2]}'
# → {"results":[42]}
```

---

### `POST /wasm/modules/{id}/wasi`

Run a WASI module, capture its stdout.

**Request:**
```json
{
  "argv": ["odin", "check", "."],
  "env":  {"MY_VAR": "value"}
}
```

**Response `200`:**
```json
{"output":"Checking 'src/main.odin'... OK\n"}
```

**Response `500` (non-zero exit):**
```json
{"error":"WASI module 'odin-verifier' exited 1\nstderr: error: ...","exitCode":1}
```

---

## Sandbox Model

WASM execution has three layered protection boundaries:

```
WASM module call
  │
  ├─ JVM linear-memory sandbox (Chicory)
  │    Module runs on its own managed heap — cannot read/write JVM memory outside WASM
  │    All memory accesses bounds-checked by Chicory interpreter
  │
  ├─ WASI capability grants (WasiOptions)
  │    Filesystem: only paths you grant are visible (default: workspace, read-only)
  │    Network: not granted (WASI p1 has no network capability)
  │    Env vars: only the keys listed in allowedEnvVars are forwarded
  │
  └─ VSubprocessAuditService (VATN core)
       Every invocation logged: moduleId, function, timestamp, duration
       Query via GET /api/guard/sandbox-audit or ctx.getService(VSubprocessAuditService.class)
```

If a WASI module spawns a subprocess (rare), VATN's `OsSandboxWrapper` (bwrap/sandbox-exec) applies based on `VTrustLevel`.

---

## Supported Languages

Any language with a WASM target that produces `.wasm` binaries:

| Language | Target | Notes |
|----------|--------|-------|
| **Rust** | `wasm32-unknown-unknown` (pure fn) · `wasm32-wasip1` (WASI) | Best WASM support, first-class |
| **C / C++** | `wasm32-wasi` via `wasi-sdk` | Download [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) |
| **Go** | `GOOS=wasip1 GOARCH=wasm` | Go 1.21+ |
| **Zig** | `zig build-lib -target wasm32-freestanding` · `wasm32-wasi` | First-class WASM target |
| **AssemblyScript** | `npx asc` | TypeScript-like syntax |
| **Python** | CPython via wasi-sdk | Large binary (~10 MB) |

---

## Compile examples

### Rust (pure function, no WASI)

```rust
// src/lib.rs
#[no_mangle]
pub extern "C" fn add(a: i64, b: i64) -> i64 { a + b }

#[no_mangle]
pub extern "C" fn fibonacci(n: i64) -> i64 {
    if n <= 1 { return n; }
    let (mut a, mut b) = (0i64, 1i64);
    for _ in 2..=n { (a, b) = (b, a + b); }
    b
}
```

```bash
cargo build --target wasm32-unknown-unknown --release
cp target/wasm32-unknown-unknown/release/mylib.wasm .vatn/wasm/math.wasm
```

### Rust (WASI — reads filesystem, writes stdout)

```rust
// src/main.rs
fn main() {
    let args: Vec<String> = std::env::args().collect();
    println!("Checking: {}", args.get(1).unwrap_or(&"none".to_string()));
    // ... your tool logic
}
```

```bash
cargo build --target wasm32-wasip1 --release
cp target/wasm32-wasip1/release/mytool.wasm .vatn/wasm/
```

### C (WASI via wasi-sdk)

```c
#include <stdio.h>
int main(int argc, char *argv[]) {
    printf("hello from WASM: %s\n", argc > 1 ? argv[1] : "world");
    return 0;
}
```

```bash
/opt/wasi-sdk/bin/clang --sysroot=/opt/wasi-sdk/share/wasi-sysroot hello.c -o .vatn/wasm/hello.wasm
```

---

---

## Endive — the Bytecode Alliance fork

[Endive](https://github.com/bytecodealliance/endive) (`run.endive:runtime`) is a fork of Chicory adopted by the Bytecode Alliance as a vendor-neutral project. It preserves the `com.dylibso.chicory.*` package names, so the plugin compiles and runs against both JARs without any code changes.

```bash
# Build with Endive instead of Chicory:
mvn package -P endive -pl vatn-plugin-wasm

# Or set the system property:
mvn package -Dwasm.runtime=endive -pl vatn-plugin-wasm
```

**Why Endive matters — the Redline compiler:**  
Endive's roadmap includes the Redline compiler — Cranelift (the same backend as Wasmtime) compiled to WASM, then executed via Java 25's Panama FFM API to compile further to native machine code. On Java 25+, this delivers GraalWASM-level performance with **zero additional dependencies** — no GraalVM JDK required. When Redline ships, switching from Chicory to Endive will be a one-line pom change.

**Maven coordinates (`run.endive`):**
```xml
<!-- Set endive.version in vatn-plugin-wasm/pom.xml -->
<properties>
    <endive.version>1.0.0</endive.version>
</properties>

<!-- Already wired in the -P endive profile: -->
<dependency>
    <groupId>run.endive</groupId>
    <artifactId>runtime</artifactId>
    <version>${endive.version}</version>
</dependency>
<dependency>
    <groupId>run.endive</groupId>
    <artifactId>wasi</artifactId>
    <version>${endive.version}</version>
</dependency>
```

If Endive is not yet on Maven Central, use JitPack (add repository `https://jitpack.io`, change groupId to `com.github.bytecodealliance`, artifactId to `endive`).

---

## Benchmark — choosing the right backend

The plugin ships a JMH benchmark in its test scope that measures load time, call throughput, and SPI overhead. Run it to get numbers for your specific hardware.

### Running the benchmark

```bash
# Quick benchmark (Chicory, default):
cd vatn-plugins
mvn test -pl vatn-plugin-wasm -Dtest=WasmRuntimeBenchmark -P chicory

# Same benchmark with Endive (when available):
mvn test -pl vatn-plugin-wasm -Dtest=WasmRuntimeBenchmark -P endive

# Full JMH run with reports:
mvn package -pl vatn-plugin-wasm -P chicory -DskipTests
java -jar vatn-plugin-wasm/target/benchmarks.jar -f 2 -wi 5 -i 5 -rff results.csv
```

### Expected results (Apple M-series, Java 25, small 41-byte add module)

| Benchmark | Chicory (interpreter) | Endive + Redline (future) | GraalWASM (GraalVM JDK) |
|-----------|----------------------|--------------------------|-------------------------|
| `loadModule` | 2–8 ms | ~same | ~same |
| `callAdd_twoI64` | 8–25 M calls/s | 150–500 M calls/s | 200–500 M calls/s |
| `callAdd_largeValues` | ~same | ~same | ~same |
| `callViaRuntime` (SPI overhead) | < 2% vs raw | < 2% | < 2% |

**Load time is amortized.** Load once at startup, call millions of times.

### Decision guide

```
You need...                          → Use
─────────────────────────────────────────────────────────────────
Reliable, production-ready today     → Chicory (default)
Future native perf, no GraalVM dep   → Endive (when Redline ships)
Peak perf, already on GraalVM JDK   → GraalWASM (-P graalwasm)
Run a verifier/linter once per PR    → Any — load cost dominates
High-throughput inference in WASM   → GraalWASM or Endive+Redline
Must run on OpenJDK (not GraalVM)   → Chicory or Endive+Redline
```

---

## Upgrading Chicory

Chicory's version is pinned in a **single property** in `vatn-plugins-parent/pom.xml`:

```xml
<chicory.version>1.7.5</chicory.version>
```

To upgrade:
1. Change `chicory.version` to the new version
2. Check [Chicory release notes](https://github.com/dylibso/chicory/releases) for API changes
3. If `ExportFunction`, `Store`, or `WasiOptions` API changed, update `ChicoryWasmModule.java`
4. Run `mvn compile -pl vatn-plugin-wasm` to verify
5. Commit — git history shows exactly which version is running

All other plugins are unaffected.

---

## Switching to GraalWASM

The `VWasmRuntime` SPI is the abstraction point. To switch:

1. Add a `GraalWasmRuntime` class that implements `VWasmRuntime` using `org.graalvm.polyglot.Context`
2. In `WasmPlugin.onInitialize`, replace:
   ```java
   runtime = new ChicoryWasmRuntime(config, workspacePath);
   ```
   with:
   ```java
   // Optional: detect GraalVM at runtime and prefer it
   runtime = isGraalVm()
       ? new GraalWasmRuntime(config, workspacePath)
       : new ChicoryWasmRuntime(config, workspacePath);
   ```
3. Add `graalvm-sdk` dependency (provided scope when on GraalVM JDK)

All callers (`ctx.getService(VWasmRuntime.class)`) continue working unchanged.

GraalWASM advantage: JIT compilation → native machine code → peak performance.  
GraalWASM requirement: must run on GraalVM JDK; falls back to slow interpreter on OpenJDK.

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `com.dylibso.chicory:runtime:${chicory.version}` | WASM bytecode interpreter + AOT compiler |
| `com.dylibso.chicory:wasi:${chicory.version}` | WASI Preview 1 syscall implementation |
| `dev.vatn:vatn-api` (provided) | VATN plugin interfaces |
| `com.fasterxml.jackson.core:jackson-databind` | REST endpoint JSON |
| `org.slf4j:slf4j-api` | Logging |
