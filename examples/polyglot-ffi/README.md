# Vatn FFI Demo — Foreign Tool Integration

Three patterns for calling non-Java code from a Vatn `@AgentPlugin`,
ordered from lowest to highest integration cost.

```
vatn-ffi-demo/
├── c-tool/
│   └── vatn_text_tool.c          ← C shared library (text processing)
├── odin-tool/
│   └── vatn_fs_guard.odin        ← Odin shared library (filesystem guard)
├── python-tool/
│   └── vatn_analysis_tool.py     ← Python script (ML/text analysis)
└── java-plugin/
    ├── pom.xml
    └── src/main/java/dev/vatn/plugin/ffi/
        ├── NativeLibLoader.java         ← shared FFM loader utility
        ├── VatnFfiDemo.java           ← demo runner / integration test
        ├── c/
        │   └── VatnTextTool.java      ← wraps C lib via FFM
        ├── odin/
        │   └── VatnFsGuardTool.java   ← wraps Odin lib via FFM
        └── python/
            └── VatnPythonAnalysisTool.java ← wraps Python via ProcessBuilder
```

---

## The Three Patterns

### Pattern 1 — C via Java 25 FFM (Foreign Function & Memory API)

**When to use:** raw numeric speed, wrapping existing C/C++ libraries,
platform-specific syscalls, anything that exposes a C ABI.

**How it works:**

```
Java MethodHandle  →  Linker (knows platform ABI)  →  C function in .so/.dll
                   ←  return value copied back      ←
```

No JNI header generation. No `native` keyword. No `javah`. Just:

```java
SymbolLookup lib = SymbolLookup.libraryLookup(path, Arena.global());

MethodHandle fn = Linker.nativeLinker().downcallHandle(
    lib.find("my_function").orElseThrow(),
    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
);

try (Arena arena = Arena.ofConfined()) {
    MemorySegment arg = arena.allocateFrom("hello");
    int result = (int) fn.invoke(arg);
}
```

**Memory model:**
- `Arena.ofConfined()` — per-call, freed at end of try block (most common)
- `Arena.ofShared()`   — multi-thread shared, explicit close required
- `Arena.global()`     — lives forever (use for library handles)

**Build the C library:**
```bash
# Linux / WSL
gcc -O2 -shared -fPIC -o native/libvatn_text_tool.so c-tool/vatn_text_tool.c

# macOS
gcc -O2 -shared -fPIC -o native/libvatn_text_tool.dylib c-tool/vatn_text_tool.c

# Windows (MSVC)
cl /O2 /LD c-tool\vatn_text_tool.c /Fe:native\vatn_text_tool.dll
```

---

### Pattern 2 — Odin via Java 25 FFM (C ABI export)

**When to use:** Odin, Rust, Zig, or any language that can export a C ABI.
From Java's perspective this is *identical* to Pattern 1 — the only
difference is how you compile the library.

Odin exports with a C ABI using two annotations on the proc:

```odin
@(export, link_name="my_function")
my_function :: proc "c" (arg: cstring) -> i32 {
    // ...
}
```

`proc "c"` = use the C calling convention.
`@(export, link_name=...)` = export under a stable C symbol name.

**Build the Odin library:**
```bash
# Linux
odin build odin-tool/vatn_fs_guard.odin \
    -file -build-mode:shared \
    -out:native/libvatn_fs_guard.so

# macOS
odin build odin-tool/vatn_fs_guard.odin \
    -file -build-mode:shared \
    -out:native/libvatn_fs_guard.dylib

# Windows
odin build odin-tool\vatn_fs_guard.odin ^
    -file -build-mode:shared ^
    -out:native\vatn_fs_guard.dll
```

The Java side (VatnFsGuardTool.java) is byte-for-byte identical in
structure to the C wrapper — same SymbolLookup, same FunctionDescriptor,
same MethodHandle pattern.

**VatnGuard integration:**
The Odin guard tool returns typed verdicts (ALLOW / BLOCK_* / REQUIRE_APPROVAL)
that map directly to VatnGuard's three-verdict model. Wire it like this:

```java
// In your VatnGuard rule evaluator:
VatnFsGuardTool nativeGuard = context.getTool(VatnFsGuardTool.class);
Verdict verdict = nativeGuard.evaluate(requestedPath, workspaceRoot);

return switch (verdict) {
    case ALLOW            -> GuardDecision.ALLOW;
    case REQUIRE_APPROVAL -> GuardDecision.REQUIRE_APPROVAL;
    default               -> GuardDecision.BLOCK;
};
```

---

### Pattern 3 — Python via ProcessBuilder stdio

**When to use:** reusing existing Python ML/data-science tooling, scripts
that rely on pip packages (pandas, transformers, spacy, scikit-learn),
or any language that is easier to run as a subprocess than to embed.

**How it works:**

```
Java ProcessBuilder  →  python3 script.py
  write JSON to stdin →
                       ← read JSON from stdout
Process.waitFor()    ←
```

Protocol (one request per process):
```json
// stdin  (Java to Python)
{ "action": "sentiment", "params": { "text": "..." } }

// stdout (Python to Java)
{ "ok": true, "result": { "label": "positive", "score": 0.87 } }
```

**Why not embed CPython?**
FFM can call CPython's C API, but you must manage the GIL manually,
marshal Python objects to/from C types, and handle CPython version drift.
For most tool use cases a subprocess is far simpler and more maintainable.
The subprocess model also means a crashing Python script cannot take down the JVM.

**Extending the Python tool:**
Swap the simple lexicon sentiment analyser for a real model by changing
only the Python file — no Java recompile needed:

```python
# drop-in upgrade for sentiment() in vatn_analysis_tool.py
from transformers import pipeline
_pipe = pipeline("sentiment-analysis")

def sentiment(params):
    result = _pipe(params["text"])[0]
    return {"label": result["label"].lower(), "score": round(result["score"], 3)}
```

---

## Running the Demo

```bash
# 1. Build native libraries (Linux example)
mkdir -p java-plugin/native

gcc -O2 -shared -fPIC \
    -o java-plugin/native/libvatn_text_tool.so \
    c-tool/vatn_text_tool.c

odin build odin-tool/vatn_fs_guard.odin \
    -file -build-mode:shared \
    -out:java-plugin/native/libvatn_fs_guard.so

# 2. Run the Java demo (Python demo works without native libs)
cd java-plugin
mvn compile exec:java \
    -Dexec.mainClass="dev.vatn.plugin.ffi.VatnFfiDemo" \
    -DNATIVE_DIR=native
```

Critical JVM flag — required for FFM to call native code:
```
--enable-native-access=ALL-UNNAMED
```
The pom.xml already adds this via exec-maven-plugin and maven-surefire-plugin.

---

## Comparison Table

| Concern             | C/Odin via FFM              | Python via ProcessBuilder    |
|---------------------|-----------------------------|------------------------------|
| Latency             | Microseconds (in-process)   | Milliseconds (fork + JSON)   |
| Memory sharing      | Direct (MemorySegment)      | JSON serialisation only      |
| Crash isolation     | None — JVM crash on segfault| Full — subprocess is isolated|
| Library reuse       | Any C ABI library           | Any pip package              |
| Build step          | Compile to .so/.dll         | None (interpreted)           |
| Best for            | Speed, system integration   | ML models, existing scripts  |

---

## Mapping to Vatn Concepts

| FFI concept               | Vatn equivalent                            |
|---------------------------|----------------------------------------------|
| `@AgentPlugin`            | Tool registered in PF4J plugin registry      |
| `ToolDispatcher`          | Catches LLM tool calls, routes to plugin     |
| `VatnGuard`             | Sits between dispatcher and execution        |
| `REQUIRE_APPROVAL` verdict| Parks virtual thread via HITL (D-12)         |
| `NativeLibLoader`         | Ships .so next to plugin JAR in plugin dir   |
| `McpBridgeTool` (D-19)    | Alternative: expose Python tool as MCP server|
| `Vatn MCP Server` (D-21)| Re-exports all @AgentPlugins over MCP        |

Any plugin implemented via Pattern 1, 2, or 3 is automatically exposed
over the Vatn MCP server (D-21) with zero additional code — the MCP
manifest is generated from the PF4J registry at runtime.
