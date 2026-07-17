#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# VATN Benchmark Report Generator
# Combines the latest HTTP and workflow results into a single Markdown report.
#
# Usage:
#   ./bench/report/generate.sh
#
# Requires: jq (for parsing JMH JSON output)
# Output: results/VATN-Benchmark-Report-<timestamp>.md
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

RESULTS_DIR="$(dirname "$0")/../../results"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="$RESULTS_DIR/VATN-Benchmark-Report-$TIMESTAMP.md"

# Find latest result files
HTTP_RESULT="$(ls -t "$RESULTS_DIR"/http/vatn-http-*.txt 2>/dev/null | head -1 || true)"
DAG_JSON="$(ls -t "$RESULTS_DIR"/workflow/vatn-dag-*.json 2>/dev/null | head -1 || true)"

mkdir -p "$RESULTS_DIR"

{
cat <<EOF
# VATN Benchmark Report

Generated: $(date)
Host: $(uname -srm)
Java: $(java -version 2>&1 | head -1)

---

## 1. HTTP Throughput (req/sec)

| Endpoint        | VATN (this run) | Node.js/Express | Go/Fiber  | Rust/Actix |
|-----------------|-----------------|-----------------|-----------|------------|
| GET /ping       | _see below_     | ~78,000         | ~338,000  | ~320,000   |
| GET /json       | _see below_     | ~78,000         | ~338,000  | ~320,000   |
| POST /echo      | _see below_     | ~78,000         | ~338,000  | ~320,000   |

> Competitor numbers: TechEmpower Round 23 (JSON endpoint, single node).
> Source: https://www.techempower.com/benchmarks/#section=data-r23

EOF

if [[ -n "$HTTP_RESULT" ]]; then
    echo "### Raw wrk output"
    echo '```'
    cat "$HTTP_RESULT"
    echo '```'
else
    echo "_No HTTP benchmark results found. Run: bench/http/run.sh_"
fi

cat <<EOF

---

## 2. DAG Workflow Latency (ms per run completion)

| Benchmark                 | VATN (this run) | Windmill ~2,400 ms | Prefect ~4,900 ms | Airflow ~56,000 ms |
|---------------------------|-----------------|---------------------|--------------------|--------------------|
| trigger_latency_single    | _see below_     | n/a (1 task)        | n/a                | n/a                |
| serial_10_tasks           | _see below_     | ~600 ms (est)       | ~1,225 ms (est)    | ~14,000 ms (est)   |
| fanout_10_tasks           | _see below_     | ~600 ms (est)       | ~1,225 ms (est)    | ~14,000 ms (est)   |
| xcom_pipeline_5_tasks     | _see below_     | ~300 ms (est)       | ~612 ms (est)      | ~7,000 ms (est)    |
| fanout_api_10_runs        | _see below_     | ~600 ms (est)       | ~1,225 ms (est)    | ~14,000 ms (est)   |

> Competitor numbers: Windmill benchmark blog (40 lightweight tasks → divided by 4 for 10-task estimate).
> Source: https://windmill.dev/blog/benchmarks-workflow-engines

EOF

if [[ -n "$DAG_JSON" ]] && command -v jq &>/dev/null; then
    echo "### JMH results (average ms per operation)"
    echo '```'
    jq -r '.[] | "  \(.benchmark | split(".") | last | .[0:40]) \t\(.primaryMetric.score | . * 100 | round / 100) ± \(.primaryMetric.scoreError | . * 100 | round / 100) ms"' "$DAG_JSON"
    echo '```'
elif [[ -n "$DAG_JSON" ]]; then
    echo "### Raw JMH JSON"
    echo '```json'
    cat "$DAG_JSON"
    echo '```'
else
    echo "_No workflow benchmark results found. Run: bench/workflow/run.sh_"
fi

cat <<EOF

---

## Notes

- VATN runs on JVM with Helidon 4 SE and virtual threads (no carrier-thread blocking).
- All workflow state persisted to SQLite (same as production default).
- Competitor numbers are from public benchmarks on different hardware; use for order-of-magnitude guidance only.
- To improve VATN DAG throughput: pool size, SQLite WAL mode, in-memory XCom option.

EOF

} > "$REPORT"

echo "[VATN-BENCH] Report generated: $REPORT"
