# vatn-plugin-node

Sandboxed Node.js process management for VATN nodes — same `run[]` script format as `vatn-plugin-python`, supervised daemon processes with auto-restart, and a live admin UI with log streaming.

> **Admin UI** → `GET /node/ui`  
> **Runtime** → VATN 1.0-alpha.14+  
> **Script file** → `vatn-node.json` or `pinokio.json`

---

## Why this plugin?

- **Unified script format** — same `run[]` JSON as `vatn-plugin-python`; teams use one mental model for both runtimes
- **Pure Java supervision** — no separate PM2 daemon, no Node.js daemon process to manage; VATN virtual threads own the lifecycle
- **npm/npx built-in** — package installation, script runners, and CLI tools via dedicated methods
- **VATN security stack** — env secrets filtered, every exec logged to `VSubprocessAuditService`
- **Admin UI** — dark-theme interface with live process table, log streaming, restart button

---

## Installation

```java
VNodeRunner.create(8080)
    .addPlugin(new NodePlugin())        // default config — auto-detects node/npm/npx
    .addPlugin(new MyPlugin())
    .start();
```

On startup:
```
[NODE] Found: node → v22.4.0
[NODE] npm: npm → 10.8.1
[NODE] npx: npx
[NODE] 0 app(s) found
Node.js plugin ready — v22.4.0 | npm=10.8.1 | apps=0 | UI: /node/ui
```

---

## Directory layout

```
.vatn/
└── node/
    └── apps/
        ├── api-server/             ← app root; scripts run relative to here
        │   ├── vatn-node.json      ← the run[] script
        │   ├── package.json
        │   ├── node_modules/       ← created by npm install
        │   └── server.js
        └── worker/
            ├── vatn-node.json
            └── worker.js
```

---

## Script format (`vatn-node.json`)

Uses the same `run[]` array as `vatn-plugin-python`. The filename is `vatn-node.json` (preferred) or `pinokio.json` (for compatibility with Pinokio-format repos that include Node.js steps).

### Minimal example: Express server

```json
{
  "run": [
    {
      "method": "npm.install",
      "params": { "packages": ["express"] }
    },
    {
      "method": "shell.run",
      "params": {
        "message": "node server.js",
        "daemon": true,
        "autoRestart": true,
        "env": { "PORT": "3001" }
      }
    }
  ]
}
```

### Full example: Next.js app with build step

```json
{
  "run": [
    {
      "method": "npm.install",
      "params": {}
    },
    {
      "method": "shell.run",
      "params": {
        "message": "npm run build"
      }
    },
    {
      "method": "shell.run",
      "params": {
        "message": "node .next/standalone/server.js",
        "daemon": true,
        "autoRestart": true,
        "env": { "PORT": "3000", "HOSTNAME": "0.0.0.0" }
      }
    }
  ]
}
```

### Supported methods

| Method | What it does | Key params |
|--------|-------------|------------|
| `shell.run` | Run any shell command | `message`, `path`, `env`, `daemon`, `autoRestart` |
| `npm.install` | `npm install [packages]` in app dir | `packages` (string list, optional) |
| `npx.run` | `npx <command>` in app dir | `message` |
| `fs.write` | Write a file (scoped to app root) | `path`, `data` |
| `fs.read` | Read a file, store via `id` | `path` |
| `local.set` | Set a session variable | `key`, `value` |
| `local.get` | Get a session variable into `id` | `key` |
| `script.return` | Stop execution at this step | — |

### `shell.run` params reference

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `message` | string | required | Shell command; `node`/`npm`/`npx` replaced with detected binaries |
| `path` | string | app root | Working directory (relative to app root) |
| `env` | object | `{}` | Extra env vars (merged with policy) |
| `daemon` | boolean | false | Keep running after script ends |
| `autoRestart` | boolean | false | Auto-restart on crash |

### Template interpolation

```json
{ "method": "fs.read", "params": { "path": "./VERSION" }, "id": "ver" },
{ "method": "shell.run", "params": { "message": "echo Starting v{{ver}}" } }
```

---

## Real-world examples

### 1. Express REST API

**`vatn-node.json`:**
```json
{
  "run": [
    { "method": "npm.install", "params": { "packages": ["express", "cors", "dotenv"] } },
    { "method": "fs.write",    "params": { "path": ".env", "data": "PORT=3001\n" } },
    { "method": "shell.run",   "params": {
        "message": "node index.js",
        "daemon": true, "autoRestart": true,
        "env": { "NODE_ENV": "production" }
    }}
  ]
}
```

### 2. Background worker (Redis queue consumer)

```json
{
  "run": [
    { "method": "npm.install", "params": { "packages": ["bullmq", "ioredis"] } },
    { "method": "shell.run",   "params": {
        "message": "node worker.js",
        "daemon": true, "autoRestart": true,
        "env": { "REDIS_URL": "redis://localhost:6379" }
    }}
  ]
}
```

### 3. npx one-liner tool

```json
{
  "run": [
    { "method": "npx.run", "params": { "message": "create-react-app myapp" } }
  ]
}
```

### 4. Generate config file then start

```json
{
  "run": [
    { "method": "npm.install", "params": {} },
    { "method": "fs.write", "params": {
        "path": "config/runtime.json",
        "data": { "workers": 2, "timeout": 30000 }
    }},
    { "method": "shell.run", "params": {
        "message": "node --config config/runtime.json main.js",
        "daemon": true, "autoRestart": true
    }}
  ]
}
```

### 5. Multi-process app (API + worker simultaneously)

Run the script twice with different names, or use separate daemon steps:

```json
{
  "run": [
    { "method": "npm.install", "params": {} },
    { "method": "shell.run", "params": {
        "message": "node api.js", "daemon": true, "autoRestart": true,
        "env": { "PORT": "3000" }
    }},
    { "method": "shell.run", "params": {
        "message": "node worker.js", "daemon": true, "autoRestart": true
    }}
  ]
}
```

---

## REST API — complete reference

All endpoints at `http://localhost:8080/node/...`.

### `GET /node/status`

```bash
curl http://localhost:8080/node/status | jq
```
```json
{
  "node": "node",
  "version": "v22.4.0",
  "healthy": true,
  "npm": "10.8.1",
  "npx": "npx",
  "appCount": 2,
  "processCount": 1
}
```

---

### `GET /node/apps`

```bash
curl http://localhost:8080/node/apps | jq
```
```json
{
  "apps": [
    { "name": "api-server", "hasScript": true, "hasPackageJson": true, "runningProcesses": 1 },
    { "name": "worker",     "hasScript": true, "hasPackageJson": true, "runningProcesses": 0 }
  ],
  "count": 2
}
```

---

### `POST /node/apps/{name}/install`

Run `npm install` in the app directory. Optionally add specific packages.

```bash
# npm install (from existing package.json)
curl -X POST http://localhost:8080/node/apps/api-server/install \
  -H "Content-Type: application/json" -d '{}'

# npm install express cors
curl -X POST http://localhost:8080/node/apps/api-server/install \
  -H "Content-Type: application/json" \
  -d '{"packages": ["express", "cors"]}'
```
```json
{ "status": "installed", "app": "api-server", "packages": ["express", "cors"] }
```

---

### `POST /node/apps/{name}/run`

Execute `vatn-node.json` (or `pinokio.json`). Daemon steps start in the background; the call returns after all blocking steps complete.

```bash
curl -X POST http://localhost:8080/node/apps/api-server/run | jq
```
```json
{
  "status": "completed",
  "stepsRun": 3,
  "daemonProcessIds": ["api-server-b3c4d5e6"]
}
```

---

### `GET /node/processes`

```bash
curl http://localhost:8080/node/processes | jq
```
```json
{
  "processes": [
    {
      "id": "api-server-b3c4d5e6",
      "appId": "api-server",
      "status": "running",
      "pid": 45678,
      "restartCount": 0,
      "lastExitCode": 0,
      "startedAt": "2026-05-29T11:00:00Z",
      "autoRestart": true
    }
  ],
  "count": 1
}
```

---

### `GET /node/processes/{id}/status`

```bash
curl http://localhost:8080/node/processes/api-server-b3c4d5e6/status | jq
```
```json
{
  "id": "api-server-b3c4d5e6",
  "status": "running",
  "pid": 45678,
  "restartCount": 0,
  "logs": [
    "[VATN] Node.js process started (pid=45678)",
    "Server listening on port 3001",
    "GET /api/health 200 4ms"
  ]
}
```

---

### `POST /node/processes/{id}/stop`

```bash
curl -X POST http://localhost:8080/node/processes/api-server-b3c4d5e6/stop
```
```json
{ "status": "stopping", "id": "api-server-b3c4d5e6" }
```

---

### `POST /node/processes/{id}/restart`

Stop and immediately restart the process (useful after code changes).

```bash
curl -X POST http://localhost:8080/node/processes/api-server-b3c4d5e6/restart
```
```json
{ "status": "restarting", "id": "api-server-b3c4d5e6" }
```

---

### `GET /node/ui`

Open in browser:
```
http://localhost:8080/node/ui
```

---

## `vatn.toml` configuration (recommended for production)

```toml
# .vatn/vatn.toml

[node]
apps_dir    = "/data/vatn/node/apps"   # where vatn-node.json app dirs live
node_binary = "node"                   # override auto-detection
npm_binary  = "npm"
npx_binary  = "npx"
```

Edit and save from the **admin UI** → ⚙ Storage & Configuration panel at `GET /node/ui`. Changes are written to `.vatn/vatn.toml` and take effect after VATN restarts.

> **Disk space:** `node_modules` is typically 100–500 MB per app. If you have many large apps, point `apps_dir` to a dedicated volume.

**Resolution order**: programmatic constructor > `.vatn/vatn.toml [node]` > built-in default.

---

## Configuration reference (programmatic)

```java
new NodePlugin(NodeConfig.builder()
    .nodeBinary("node")                         // null = auto-detect
    .npmBinary("npm")
    .npxBinary("npx")
    .appsDir(Paths.get(".vatn/node/apps"))
    .restartDelayMs(3_000)                      // ms before auto-restart on crash
    .maxLogLines(500)                           // circular log buffer per process
    .allowNetwork(true)                         // allow network in daemon processes
    .allowedEnvVars(List.of("PATH", "HOME", "NODE_ENV", "CI", "LANG"))
    .build())
```

| Option | Default | Description |
|--------|---------|-------------|
| `nodeBinary` | auto | Tries `node`, then `nodejs` |
| `npmBinary` | auto | Tries `npm` |
| `npxBinary` | auto | Tries `npx` |
| `appsDir` | `.vatn/node/apps` | Root for all app directories |
| `restartDelayMs` | `3000` | Wait before auto-restart after crash |
| `maxLogLines` | `500` | Per-process stdout/stderr ring buffer |
| `allowNetwork` | `true` | Allow outbound network in daemon processes |
| `allowedEnvVars` | PATH, HOME, NODE_ENV, CI, LANG | Host vars passed to subprocesses |

---

## Security model

Same three-layer stack as `vatn-plugin-python`:

```
Your vatn-node.json step
    │
    ▼ ShellEnvPolicy (vatn-core)
    Filters host env: keeps allowedEnvVars, blocks *_KEY *_TOKEN *_SECRET etc.
    Reads [sandbox.shell_env] from .vatn/vatn.toml
    │
    ▼ OsSandboxWrapper (vatn-core)
    macOS: sandbox-exec  [for SANDBOXED trust level]
    Linux: bwrap          [for SANDBOXED trust level]
    Daemon processes run with FULL trust by default (they need network/filesystem)
    │
    ▼ VSubprocessAuditService (vatn-core)
    Every exec logged: nodeId, command, exitCode, durationMs, timestamp
    Query: GET /api/guard/sandbox-audit
```

Path containment: `fs.write` and `fs.read` reject paths that resolve outside the app root.

---

## Why not PM2?

| | PM2 | vatn-plugin-node |
|---|---|---|
| Process supervision | ✅ | ✅ |
| Auto-restart | ✅ | ✅ |
| Log access | ✅ (files) | ✅ (in-memory, REST queryable) |
| Java JVM visibility | ❌ Separate process | ✅ Virtual threads |
| Audit logging | ❌ | ✅ VSubprocessAuditService |
| VATN security policies | ❌ | ✅ ShellEnvPolicy + OsSandboxWrapper |
| Cluster mode | ✅ | ❌ Use daemon steps for multiple processes |
| Separate daemon | ✅ (always running) | ❌ (lifecycle owned by VATN node) |
| Admin UI | ✅ PM2 Plus (paid) | ✅ Built-in at /node/ui |

---

## Why not Node-RED?

Node-RED is a **visual flow-based programming environment** for wiring IoT sensors and APIs graphically. It is not a general-purpose Node.js process runner.

`vatn-plugin-node` runs **any** Node.js application — Express servers, workers, CLI tools, scrapers, Next.js apps — with the same declarative `run[]` format you use for Python apps.

---

## Troubleshooting

**`No Node.js interpreter found`**  
→ Install Node.js 18+ from [nodejs.org](https://nodejs.org) or via nvm: `nvm install 22`. Ensure `node` is in PATH.

**`npm install` fails with ENOENT**  
→ The app directory doesn't exist yet. Call `POST /node/apps/{name}/install` first, which creates it automatically.

**Process crashes immediately (restartCount > 0 quickly)**  
→ Check logs: `GET /node/processes/{id}/status` → `logs` array. The first lines show the crash reason. Fix the script or add missing env vars.

**`Cannot find module 'express'`**  
→ The npm install step didn't run, or ran in a different directory. Add `{ "method": "npm.install", "params": { "packages": ["express"] } }` before the shell.run step.

**Process shows `stopped` after `daemon: true`**  
→ The process exited immediately. Check logs. Common causes: missing env vars, wrong file path in the command, port already in use.

**`fs.write path escapes app root`**  
→ Use relative paths only in the `path` param. Absolute paths and `../` traversal are blocked.
