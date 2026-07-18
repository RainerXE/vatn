# vatn-plugin-python

Sandboxed Python process management for VATN nodes — with **Pinokio-compatible `run[]` script format**, virtual environment management (uv / pip / conda), supervised daemon processes, and a live admin UI.

> **Admin UI** → `GET /python/ui`  
> **Runtime** → VATN 1.0-alpha.14+  
> **Pinokio compatibility** → reads `pinokio.json` scripts directly

---

## Why this plugin?

Running Python from a Java service traditionally means: shell-out chaos, no environment isolation, no process supervision, and no visibility into what ran. This plugin fixes all of that:

- **One-line install** in `VNodeRunner` — Python is then available as a `VService` to every plugin
- **Pinokio format** — the same `run[]` JSON used by thousands of existing AI app installers works here without modification
- **Venv isolation** — each app gets its own virtual environment; packages never conflict
- **Supervised daemons** — crash? auto-restart with configurable backoff; always live in the admin UI
- **VATN security stack** — env secrets filtered by `ShellEnvPolicy`, OS isolation via `OsSandboxWrapper`, every exec logged to `VSubprocessAuditService`

---

## Installation

```java
VNodeRunner.create(8080)
    .addPlugin(new PythonPlugin())      // default config — auto-detects python3/uv/conda
    .addPlugin(new MyPlugin())
    .start();
```

On startup you will see:
```
[PYTHON] Found: python3 → Python 3.12.4
[PYTHON] uv found: uv 0.4.1
[PYTHON] 0 venv(s) loaded
Python plugin ready — Python 3.12.4 | uv=true | envs=0 | UI: /python/ui
```

Or with a warning if Python is missing:
```
[PYTHON] No Python interpreter found — plugin will be limited
```

---

## Directory layout

Everything is scoped to your VATN workspace:

```
.vatn/
├── python/
│   ├── envs/
│   │   ├── myapp/          ← venv created by createEnv("myapp")
│   │   │   ├── bin/python
│   │   │   └── lib/
│   │   └── another-app/
│   └── apps/
│       ├── myapp/          ← app root; all paths in scripts are relative to here
│       │   ├── pinokio.json
│       │   ├── requirements.txt
│       │   └── server.py
│       └── another-app/
│           └── pinokio.json
└── vatn.toml               ← [sandbox.shell_env] section controls env filtering
```

---

## Pinokio script format

Scripts are JSON files with a `run[]` array — **identical to the format used by [Pinokio](https://github.com/pinokiocomputer/pinokio)**. Drop any Pinokio-format app into `.vatn/python/apps/<name>/` and run it.

### Minimal example: install and run a FastAPI server

```json
{
  "run": [
    {
      "method": "shell.run",
      "params": {
        "message": "uv pip install fastapi uvicorn",
        "venv": "myapp"
      }
    },
    {
      "method": "shell.run",
      "params": {
        "message": "uvicorn main:app --host 0.0.0.0 --port 8001",
        "venv": "myapp",
        "daemon": true,
        "autoRestart": true
      }
    }
  ]
}
```

### Full example: config, secrets, multiple steps

```json
{
  "run": [
    {
      "method": "fs.write",
      "params": {
        "path": ".env",
        "data": "MODEL_NAME=mistral\nCACHE_DIR=./cache\n"
      }
    },
    {
      "method": "shell.run",
      "params": {
        "message": "uv pip install -r requirements.txt",
        "path": "./app",
        "venv": "llm-server",
        "env": { "PIP_NO_CACHE_DIR": "1" }
      }
    },
    {
      "method": "fs.read",
      "params": { "path": "./config/port.txt" },
      "id": "port"
    },
    {
      "method": "shell.run",
      "params": {
        "message": "python server.py --port {{port}}",
        "path": "./app",
        "venv": "llm-server",
        "daemon": true,
        "autoRestart": true,
        "env": { "CUDA_VISIBLE_DEVICES": "0" }
      }
    }
  ]
}
```

### Supported methods

| Method | What it does |
|--------|-------------|
| `shell.run` | Run a shell command — blocking or daemon |
| `fs.write` | Write a file (scoped to app root, no path escape) |
| `fs.read` | Read a file; result stored as local variable via `id` |
| `local.set` | Set a named session variable |
| `local.get` | Get a session variable, store via `id` |
| `script.return` | Stop execution at this step |

### `shell.run` params reference

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `message` | string | required | The shell command to run |
| `path` | string | app root | Working directory (relative to app root) |
| `venv` | string | — | Virtual env name; auto-created if missing |
| `conda` | string | — | Conda env name (requires conda in PATH) |
| `env` | object | `{}` | Extra environment variables (merged with policy) |
| `daemon` | boolean | false | Keep running after script ends |
| `autoRestart` | boolean | false | Auto-restart if daemon crashes |

### Template interpolation

Reference outputs from earlier steps using `{{id}}`:

```json
{ "method": "shell.run", "params": { "message": "python -c \"import sys; print(sys.version)\"" }, "id": "pyver" },
{ "method": "shell.run", "params": { "message": "echo Python version is {{pyver}}" } }
```

---

## Real-world examples

### 1. vllm inference server

```json
{
  "run": [
    { "method": "shell.run", "params": { "message": "uv pip install vllm", "venv": "vllm" } },
    { "method": "shell.run", "params": {
        "message": "python -m vllm.entrypoints.openai.api_server --model mistralai/Mistral-7B-v0.1 --port 8001",
        "venv": "vllm", "daemon": true, "autoRestart": true,
        "env": { "CUDA_VISIBLE_DEVICES": "0", "HF_HOME": "./models" }
    }}
  ]
}
```

### 2. Gradio demo app

```json
{
  "run": [
    { "method": "shell.run", "params": { "message": "uv pip install gradio torch torchvision", "venv": "demo" } },
    { "method": "shell.run", "params": {
        "message": "python app.py",
        "venv": "demo", "daemon": true, "autoRestart": true,
        "env": { "GRADIO_SERVER_PORT": "7860", "GRADIO_SHARE": "false" }
    }}
  ]
}
```

### 3. Conda environment (for apps that require system packages)

```json
{
  "run": [
    { "method": "shell.run", "params": {
        "message": "conda create -n audio -y python=3.11 ffmpeg portaudio"
    }},
    { "method": "shell.run", "params": {
        "message": "pip install openai-whisper",
        "conda": "audio"
    }},
    { "method": "shell.run", "params": {
        "message": "python transcribe.py",
        "conda": "audio", "daemon": true, "autoRestart": true
    }}
  ]
}
```

### 4. Running an existing Pinokio app (e.g. Automatic1111)

```bash
# Copy the app's pinokio.json into .vatn/python/apps/a1111/
cp /path/to/AUTOMATIC1111/pinokio.json .vatn/python/apps/a1111/

# Run it
curl -X POST http://localhost:8080/python/apps/a1111/run
```

Most Pinokio apps that use `shell.run` with `venv` work directly. Apps that use `fs.*`, `local.*`, and `script.return` also work. Apps that use dynamic JS (`pinokio.js` module.exports) or the Pinokio download API need the respective steps re-written.

---

## REST API — complete reference

All endpoints served at `http://localhost:8080/python/...`.

### `GET /python/status`

Runtime health and version info.

```bash
curl http://localhost:8080/python/status | jq
```
```json
{
  "python": "python3",
  "version": "Python 3.12.4",
  "healthy": true,
  "uv": "uv 0.4.1",
  "conda": "not found",
  "envCount": 2,
  "processCount": 1
}
```

---

### `GET /python/envs`

List all virtual environments.

```bash
curl http://localhost:8080/python/envs | jq
```
```json
{
  "envs": [
    { "name": "myapp",   "path": "/workspace/.vatn/python/envs/myapp" },
    { "name": "llm-svc", "path": "/workspace/.vatn/python/envs/llm-svc" }
  ],
  "count": 2
}
```

---

### `POST /python/envs/{name}`

Create a new virtual environment (no request body required).

```bash
curl -X POST http://localhost:8080/python/envs/myapp | jq
```
```json
{ "name": "myapp", "path": "/workspace/.vatn/python/envs/myapp", "status": "created" }
```

---

### `DELETE /python/envs/{name}`

Delete a virtual environment and all its installed packages.

```bash
curl -X DELETE http://localhost:8080/python/envs/myapp
```
```json
{ "status": "deleted", "name": "myapp" }
```

---

### `GET /python/apps`

List app directories under `.vatn/python/apps/`.

```bash
curl http://localhost:8080/python/apps | jq
```
```json
{
  "apps": [
    { "name": "myapp", "path": "/workspace/.vatn/python/apps/myapp",
      "hasScript": true, "runningProcesses": 1 },
    { "name": "another", "path": "/workspace/.vatn/python/apps/another",
      "hasScript": false, "runningProcesses": 0 }
  ],
  "count": 2
}
```

---

### `POST /python/apps/{name}/run`

Execute the app's `pinokio.json` script. Returns immediately once all non-daemon steps complete; daemon processes keep running in the background.

```bash
curl -X POST http://localhost:8080/python/apps/myapp/run | jq
```
```json
{
  "status": "completed",
  "stepsRun": 3,
  "daemonProcessIds": ["myapp-a1b2c3d4"]
}
```

---

### `GET /python/processes`

All running and recently stopped processes across all apps.

```bash
curl http://localhost:8080/python/processes | jq
```
```json
{
  "processes": [
    {
      "id": "myapp-a1b2c3d4",
      "appId": "myapp",
      "status": "running",
      "pid": 12345,
      "restartCount": 0,
      "lastExitCode": 0,
      "startedAt": "2026-05-29T10:42:00Z",
      "autoRestart": true
    }
  ],
  "count": 1
}
```

---

### `GET /python/processes/{id}/status`

Status of a specific process including the last 50 log lines.

```bash
curl http://localhost:8080/python/processes/myapp-a1b2c3d4/status | jq
```
```json
{
  "id": "myapp-a1b2c3d4",
  "status": "running",
  "pid": 12345,
  "restartCount": 0,
  "logs": [
    "[VATN] Process started (pid=12345)",
    "INFO:     Started server process [12345]",
    "INFO:     Uvicorn running on http://0.0.0.0:8001 (Press CTRL+C to quit)"
  ]
}
```

---

### `POST /python/processes/{id}/stop`

Stop a running process (sends SIGTERM, then SIGKILL after 3s).

```bash
curl -X POST http://localhost:8080/python/processes/myapp-a1b2c3d4/stop
```
```json
{ "status": "stopping", "id": "myapp-a1b2c3d4" }
```

---

### `GET /python/ui`

Open the admin web UI in a browser:
```
http://localhost:8080/python/ui
```

---

## `vatn.toml` configuration (recommended for production)

The cleanest way to set storage paths — especially when environments live on a separate volume — is a `[python]` section in `.vatn/vatn.toml`. Changes are picked up on the next VATN start.

```toml
# .vatn/vatn.toml

[python]
envs_dir      = "/data/vatn/python/envs"   # where venvs are stored (can be large!)
apps_dir      = "/data/vatn/python/apps"   # where pinokio.json app dirs live
python_binary = "python3.12"               # override auto-detection
prefer_uv     = true                       # use uv pip install (10-100× faster)
```

You can also edit and save these paths from the **admin UI** → ⚙ Storage & Configuration panel at `GET /python/ui`. Changes are written to `.vatn/vatn.toml` and take effect after VATN restarts.

> **Disk space note:** Python ML environments can be large — PyTorch alone is 5–10 GB, vllm adds another 10–20 GB. Point `envs_dir` to a volume with enough headroom *before* installing heavy packages.

**Resolution order**: programmatic constructor > `.vatn/vatn.toml [python]` > built-in default.

---

## Configuration reference (programmatic)

```java
new PythonPlugin(PythonConfig.builder()
    .pythonBinary("python3.12")              // null = auto-detect
    .envsDir(Paths.get(".vatn/python/envs")) // where venvs live
    .appsDir(Paths.get(".vatn/python/apps")) // where app dirs live
    .preferUv(true)                          // true = uv pip install (faster)
    .restartDelayMs(3_000)                   // ms to wait before auto-restart
    .maxLogLines(500)                        // circular buffer per process
    .allowedEnvVars(List.of("PATH", "HOME", "LANG", "JAVA_HOME", "CI"))
    .build())
```

| Option | Default | Description |
|--------|---------|-------------|
| `pythonBinary` | auto | Try `python3`, `python`, `python3.13`… in order |
| `envsDir` | `.vatn/python/envs` | Root for all virtual environments |
| `appsDir` | `.vatn/python/apps` | Root for all app directories |
| `preferUv` | `true` | Use `uv pip install` when uv is available |
| `restartDelayMs` | `3000` | Wait this long between crash and auto-restart |
| `maxLogLines` | `500` | Per-process stdout/stderr buffer (ring buffer) |
| `allowedEnvVars` | PATH, HOME, LANG, JAVA_HOME, CI | Host env vars passed to subprocesses |

---

## Security model

Subprocess isolation runs at three layers:

```
Your pinokio.json step
    │
    ▼ ShellEnvPolicy (vatn-core)
    Filters env vars: keeps allowedEnvVars, blocks *_KEY *_TOKEN *_SECRET etc.
    Reads [sandbox.shell_env] from .vatn/vatn.toml
    │
    ▼ OsSandboxWrapper (vatn-core)
    macOS:  sandbox-exec -p "(deny file-write*)(deny network*)"  [SANDBOXED trust]
    Linux:  bwrap --ro-bind / / --unshare-all                    [SANDBOXED trust]
    (daemon processes use FULL trust by default — override in config)
    │
    ▼ VSubprocessAuditService (vatn-core)
    Every execution logged: sessionId, command, exitCode, durationMs, timestamp
    Query: GET /api/guard/sandbox-audit
```

Path containment: `fs.write` and `fs.read` reject any `path` that resolves outside the app root — no directory traversal is possible.

---

## MLX — Apple Silicon local inference and fine-tuning

On macOS with an M-series chip, `vatn-plugin-python` detects Apple Silicon at startup and reports MLX availability in the health check and admin UI (`🍎 MLX 0.21.x` badge).

**What MLX enables:**
- **Inference**: run any MLX-format model locally at 400+ tok/s — no LM Studio, no Ollama required
- **LoRA fine-tuning**: train domain adapters on local data in ~1 hour on a 16 GB MacBook
- **Full model fusion**: merge trained adapter into the base model for standalone deployment
- **OpenAI-compatible API**: `mlx_lm.server` is a drop-in replacement for Ollama's `/v1` endpoint

### Bundled MLX scripts

Three ready-to-use scripts ship with the plugin (copy to `.vatn/python/apps/<name>/`):

| Script | Purpose |
|--------|---------|
| `mlx-inference-server.json` | Install mlx-lm and start an OpenAI-compatible server at `http://localhost:8080/v1` |
| `mlx-finetune-lora.json` | LoRA fine-tuning on local JSONL data; saves adapter weights |
| `mlx-fuse-adapter.json` | Fuse trained adapter into base model; start fused model server |

### Quick start: local inference on Apple Silicon

```bash
# 1. Copy the bundled script
cp $(find ~/.m2 -name "mlx-inference-server.json" 2>/dev/null | head -1) \
   .vatn/python/apps/mlx/pinokio.json

# 2. Run it
curl -X POST http://localhost:8080/python/apps/mlx/run
# Installs mlx-lm, downloads model (~4 GB), starts server at :8080

# 3. Use it exactly like Ollama / OpenAI
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"mlx-community/Mistral-7B-Instruct-v0.3-4bit",
       "messages":[{"role":"user","content":"Hello"}]}'
```

### Recommended models (mlx-community on Hugging Face)

| Model | Size | VRAM | Speed (M3 Max) | Best for |
|-------|------|------|----------------|---------|
| `Llama-3.2-3B-Instruct-4bit` | 1.8 GB | 8 GB | ~600 tok/s | Fast responses, chat |
| `Mistral-7B-Instruct-v0.3-4bit` | 4.1 GB | 8 GB | ~400 tok/s | General coding/reasoning |
| `Qwen2.5-14B-Instruct-4bit` | 8.2 GB | 16 GB | ~200 tok/s | Complex tasks |
| `Qwen2.5-Coder-14B-Instruct-4bit` | 8.2 GB | 16 GB | ~200 tok/s | Code generation |
| `gemma-3-27b-it-4bit` | 15 GB | 32 GB | ~80 tok/s | Highest quality local |

### LoRA fine-tuning on local data

```bash
# Data format: JSONL, one JSON object per line
# data/train.jsonl:
{"messages":[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]}

# Start fine-tuning
curl -X POST http://localhost:8080/python/apps/mlx-finetune/run
# Trains for 1000 iterations (~1 hour on M3 Max for 7B model)
# Output: ./adapters/v1/*.npz
```

For full fine-tuning integration with the Frejay/Trail learning pipeline, see the [Frejay v0.5 roadmap](https://github.com/RainerXE/vatn-plugins) — the Trail trace schema generates JSONL that feeds directly into `mlx_lm.lora`.

---

## Package installer priority

| Installer | Condition | Speed |
|-----------|-----------|-------|
| `uv pip install` | `uv` in PATH + `preferUv=true` | 10–100× pip |
| `<venv>/bin/pip install` | default fallback | baseline |
| `conda run -n <env>` | `conda` param specified + conda available | varies |

Install `uv` globally once and all venv operations become significantly faster:
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

---

## Pinokio compatibility

| Pinokio feature | This plugin |
|-----------------|------------|
| `shell.run` with `venv` | ✅ Full |
| `shell.run` with `conda` | ✅ Full |
| `shell.run` with `daemon: true` | ✅ Full (supervised) |
| `shell.run` with `env` | ✅ Full (filtered by ShellEnvPolicy) |
| `shell.run` with `autoRestart` | ✅ Extension (not in original Pinokio) |
| `fs.write` / `fs.read` | ✅ Full |
| `local.set` / `local.get` | ✅ Full |
| `script.return` | ✅ Full |
| `net.fetch` (HTTP download) | ❌ Not supported — use curl in shell.run |
| Dynamic JS (`pinokio.js` module) | ❌ Not supported — JSON only |
| Pinokio marketplace / browser | ❌ Not applicable |
| `env.set` (persistent env vars) | ❌ Use `local.set` + env param |

---

## Troubleshooting

**`No Python interpreter found`**  
→ Install Python 3.10+ and ensure `python3` is in PATH. Or set `pythonBinary("python3.12")` explicitly.

**`venv creation failed`**  
→ Run `python3 -m venv --version` manually. On Debian/Ubuntu: `sudo apt install python3-venv`.

**`uv pip install` fails**  
→ Set `preferUv(false)` to fall back to pip, or install uv: `curl -LsSf https://astral.sh/uv/install.sh | sh`.

**Daemon process shows `CRASHED` immediately**  
→ Check logs via `GET /python/processes/{id}/status` — the `logs` array shows the last 50 lines including the error.

**`fs.write path escapes app root`**  
→ The `path` param resolved outside `.vatn/python/apps/<name>/`. Use relative paths only.

**Conda not found**  
→ Ensure `conda` is in PATH. Try: `conda init bash` + restart VATN node. Or use `venv` instead.
