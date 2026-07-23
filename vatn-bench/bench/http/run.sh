#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# VATN HTTP throughput benchmark
# Uses: wrk (https://github.com/wg/wrk)
#
# Usage:
#   ./bench/http/run.sh [--native] [PORT]
#
#   --native   Use the GraalVM native binary (target/vatn-bench-server) instead
#              of the JVM fat JAR. Build it first with: mvn -Pnative package
#
# Starts a VATN benchmark server on PORT (default 8080) and measures:
#   - GET /ping     plain text, no allocation
#   - GET /json     small JSON object
#   - POST /echo    request body echo
#
# Outputs: results/http-<timestamp>.txt
#
# Competitor reference numbers (TechEmpower Round 23, Fortunes endpoint):
#   ASP.NET Core:   609,000 req/s
#   Go Fiber:       338,000 req/s
#   Rust Actix-web: 320,000 req/s
#   Spring Boot:    243,000 req/s
#   Node.js/Fastify: ~90,000 req/s (estimated, JSON endpoint)
#   Node.js/Express:  78,000 req/s
#   Python/FastAPI:   ~25,000 req/s
#   Python/Django:    32,000 req/s
#
# Source: https://www.techempower.com/benchmarks/#section=data-r23
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
WRK="${WRK:-wrk}"
DURATION="${DURATION:-30s}"
THREADS="${THREADS:-4}"
CONNECTIONS="${CONNECTIONS:-100}"
RESULTS_DIR="$BENCH_DIR/results/http"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

# ── parse args ────────────────────────────────────────────────────────────────
USE_NATIVE=false
PORT=8080

for arg in "$@"; do
    case "$arg" in
        --native) USE_NATIVE=true ;;
        [0-9]*)   PORT="$arg" ;;
    esac
done

# ── choose server binary ──────────────────────────────────────────────────────
if $USE_NATIVE; then
    NATIVE_BIN="$BENCH_DIR/target/vatn-bench-server"
    if [[ ! -f "$NATIVE_BIN" ]]; then
        echo "ERROR: Native binary not found: $NATIVE_BIN"
        echo "Build it with: mvn -Pnative package -pl vatn-bench -am"
        exit 1
    fi
    SERVER_CMD=("$NATIVE_BIN" "$PORT")
    SERVER_LABEL="VATN on GraalVM native image (AOT, no JIT)"
    OUTPUT="$RESULTS_DIR/vatn-http-native-$TIMESTAMP.txt"
else
    JAR="$BENCH_DIR/target/vatn-benchmarks.jar"
    if [[ ! -f "$JAR" ]]; then
        echo "ERROR: $JAR not found. Run: mvn package -pl vatn-bench -am"
        exit 1
    fi
    SERVER_CMD=(java -cp "$JAR" dev.vatn.bench.http.BenchmarkServer "$PORT")
    SERVER_LABEL="VATN on JVM (Helidon 4 SE + virtual threads)"
    OUTPUT="$RESULTS_DIR/vatn-http-jvm-$TIMESTAMP.txt"
fi

# ── pre-flight ────────────────────────────────────────────────────────────────
if ! command -v "$WRK" &>/dev/null; then
    echo "ERROR: 'wrk' not found. Install with: brew install wrk  or  apt install wrk"
    exit 1
fi

mkdir -p "$RESULTS_DIR"

# ── start benchmark server ────────────────────────────────────────────────────
echo "[VATN-BENCH] Starting $($USE_NATIVE && echo 'native' || echo 'JVM') benchmark server on port $PORT..."
"${SERVER_CMD[@]}" &
SERVER_PID=$!

# Give Helidon time to bind
sleep $($USE_NATIVE && echo 1 || echo 2)

# Verify it's up
if ! curl -sf "http://localhost:$PORT/bench/ping" >/dev/null; then
    echo "ERROR: Server did not start on port $PORT"
    kill "$SERVER_PID" 2>/dev/null || true
    exit 1
fi
echo "[VATN-BENCH] Server ready."

# ── run wrk ───────────────────────────────────────────────────────────────────
run_wrk() {
    local label="$1"
    local url="$2"
    local method="${3:-GET}"
    echo ""
    echo "=== $label ==="
    if [[ "$method" == "POST" ]]; then
        "$WRK" -t "$THREADS" -c "$CONNECTIONS" -d "$DURATION" \
            -s "$(dirname "$0")/post.lua" "$url"
    else
        "$WRK" -t "$THREADS" -c "$CONNECTIONS" -d "$DURATION" "$url"
    fi
}

{
    echo "VATN HTTP Throughput Benchmark — $(date)"
    echo "Server: $SERVER_LABEL"
    echo "Config: $THREADS threads, $CONNECTIONS connections, $DURATION"
    echo "Host: $(uname -srm)"
    if $USE_NATIVE; then
        echo "Binary: $(du -h "$BENCH_DIR/target/vatn-bench-server" | cut -f1) (native image)"
    else
        echo "Java: $(java -version 2>&1 | head -1)"
    fi
    echo "─────────────────────────────────────────────────────────────"

    run_wrk "GET /ping (plain text)"  "http://localhost:$PORT/bench/ping"
    run_wrk "GET /json (JSON object)" "http://localhost:$PORT/bench/json"
    run_wrk "POST /echo (body echo)"  "http://localhost:$PORT/bench/echo" POST

    echo ""
    echo "─────────────────────────────────────────────────────────────"
    echo "Competitor reference (TechEmpower R23, single-node, JSON/Fortunes):"
    echo "  ASP.NET Core   : ~609,000 req/s"
    echo "  Go/Fiber       : ~338,000 req/s"
    echo "  Rust/Actix-web : ~320,000 req/s"
    echo "  Spring Boot    : ~243,000 req/s"
    echo "  Node.js/Express:  ~78,000 req/s"
    echo "  Python/FastAPI :  ~25,000 req/s"

} 2>&1 | tee "$OUTPUT"

# ── cleanup ───────────────────────────────────────────────────────────────────
kill "$SERVER_PID" 2>/dev/null || true

echo ""
echo "[VATN-BENCH] Results saved → $OUTPUT"
