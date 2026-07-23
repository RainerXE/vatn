#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# VATN DAG workflow throughput benchmark
# Uses: JMH (embedded in vatn-benchmarks.jar)
#
# Usage:
#   ./bench/workflow/run.sh [EXTRA_JMH_ARGS]
#
# Runs WorkflowDagBench and saves results alongside competitor baselines.
#
# Competitor reference (40 lightweight tasks, from public benchmarks):
#   Windmill  : ~2.4 s    (Rust-based, fastest among pure workflow engines)
#   Prefect   : ~4.9 s    (Python, modern task runner)
#   Apache Airflow: ~56 s (Python, traditional scheduler overhead)
#
# Source: https://windmill.dev/blog/benchmarks-workflow-engines
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

JAR="$(dirname "$0")/../../target/vatn-benchmarks.jar"
RESULTS_DIR="$(dirname "$0")/../../results/workflow"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT="$RESULTS_DIR/vatn-dag-$TIMESTAMP.json"
TXT_OUTPUT="$RESULTS_DIR/vatn-dag-$TIMESTAMP.txt"

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: $JAR not found. Run: mvn package -pl vatn-bench -am"
    exit 1
fi

mkdir -p "$RESULTS_DIR"

echo "[VATN-BENCH] Running WorkflowDagBench..."
echo "[VATN-BENCH] Results → $OUTPUT"
echo ""

java -jar "$JAR" \
    ".*WorkflowDagBench.*" \
    -wi 3 -i 5 -f 1 \
    -rf json -rff "$OUTPUT" \
    "$@" \
    2>&1 | tee "$TXT_OUTPUT"

# ── print comparison summary ──────────────────────────────────────────────────
echo ""
echo "────────────────────────────────────────────────────────────────────────"
echo "VATN DAG Benchmark complete. Competitor reference (40 lightweight tasks):"
echo ""
echo "  Engine         | Time (ms) | Notes"
echo "  ───────────────┼───────────┼──────────────────────────────"
echo "  VATN (this)    |    see ↑  | JVM + virtual threads + SQLite"
echo "  Windmill        |   ~2,400  | Rust, best-in-class open source"
echo "  Prefect         |   ~4,900  | Python 3, async, in-process"
echo "  Apache Airflow  |  ~56,000  | Python 3, scheduler overhead"
echo ""
echo "  VATN measures 10-task variants; scale by 4x for 40-task comparison."
echo "  Results saved to: $OUTPUT"
echo "────────────────────────────────────────────────────────────────────────"
