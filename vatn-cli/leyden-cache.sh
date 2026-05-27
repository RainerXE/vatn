#!/usr/bin/env bash
# leyden-cache.sh — Generate or use a Project Leyden AOT cache for the VATN CLI fat JAR.
#
# Defaults to the current SDKMAN java (GraalVM 25).
# Override with: JAVA_CMD=/path/to/java ./leyden-cache.sh ...
#
# IMPORTANT: The AOT cache is JVM-build-specific. You must use the exact same java
# binary to build the cache and to run with it.
#
# Usage:
#   ./leyden-cache.sh build [JAR]         — build the AOT cache next to the JAR
#   ./leyden-cache.sh run   [JAR] [args…] — run using the cache (fails fast if incompatible)
#   ./leyden-cache.sh check [JAR]         — verify the cache is valid for the current JVM
#
# The cache is written as <jar-dir>/vatn.aot
# Requires Java 25+. No GraalVM native-image needed.
#
# Examples:
#   ./leyden-cache.sh build
#   ./leyden-cache.sh run   target/vatn-cli-1.0-SNAPSHOT.jar --help
#   ./leyden-cache.sh run   target/vatn-cli-1.0-SNAPSHOT.jar run --port 8080

set -euo pipefail

# --- Resolve java executable ---
# Priority: 1) JAVA_CMD env, 2) SDKMAN current, 3) java in PATH
if [[ -z "${JAVA_CMD:-}" ]]; then
    SDKMAN_JAVA="${HOME}/.sdkman/candidates/java/current/bin/java"
    if [[ -x "$SDKMAN_JAVA" ]]; then
        JAVA_CMD="$SDKMAN_JAVA"
    else
        JAVA_CMD="java"
    fi
fi

JAVA_VER_FULL=$("$JAVA_CMD" -version 2>&1 | head -1)

# Warn if not Java 25
if ! "$JAVA_CMD" -version 2>&1 | grep -qE "version \"25"; then
    echo "[Leyden] WARNING: Expected GraalVM 25, got: $JAVA_VER_FULL" >&2
    echo "[Leyden] Switch with: sdk use java 25.0.2-graal" >&2
fi

COMMAND="${1:-}"
shift || true

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_JAR="${SCRIPT_DIR}/target/vatn-cli-1.0-SNAPSHOT.jar"
JAR="${1:-$DEFAULT_JAR}"

if [[ "$COMMAND" != "check" ]] && [[ ! -f "$JAR" ]]; then
    echo "ERROR: JAR not found: $JAR" >&2
    echo "Run 'mvn package -pl vatn-cli -am -DskipTests' first." >&2
    exit 1
fi

JAR_DIR="$(dirname "$(realpath "$JAR")")"
CONF="${JAR_DIR}/vatn.aotconf"
CACHE="${JAR_DIR}/vatn.aot"

case "$COMMAND" in
    build)
        echo "[Leyden] JVM: $JAVA_VER_FULL"
        echo "[Leyden] Phase 1/2: recording class loading profile..."
        "$JAVA_CMD" \
             -XX:AOTMode=record \
             -XX:AOTConfiguration="$CONF" \
             -jar "$JAR" \
             --version 2>&1 | grep -Ev "^\[.*\]\[(warning|info)\]\[aot\]" || true

        echo "[Leyden] Phase 2/2: creating AOT cache..."
        "$JAVA_CMD" \
             -XX:AOTMode=create \
             -XX:AOTConfiguration="$CONF" \
             -XX:AOTCache="$CACHE" \
             -jar "$JAR" \
             --version 2>&1 | grep -Ev "^\[.*\]\[(warning|info)\]\[aot\]" || true

        SIZE=$(du -sh "$CACHE" 2>/dev/null | cut -f1 || echo "?")
        echo ""
        echo "[Leyden] Cache ready: $CACHE ($SIZE)"
        echo "[Leyden] JVM: $JAVA_VER_FULL"
        echo ""
        echo "Run:"
        echo "  ./leyden-cache.sh run \"$JAR\" <args>"
        echo "  $JAVA_CMD -XX:AOTCache=\"$CACHE\" -jar \"$JAR\" <args>"
        ;;

    check)
        if [[ ! -f "$CACHE" ]]; then
            echo "[Leyden] No cache at: $CACHE"
            echo "         Build: ./leyden-cache.sh build $JAR"
            exit 1
        fi
        OUT=$("$JAVA_CMD" -Xlog:aot -XX:AOTCache="$CACHE" -jar "$JAR" --version 2>&1)
        if echo "$OUT" | grep -q "different version"; then
            EXPECTED=$(echo "$OUT" | grep "expected:" | head -1 | sed 's/.*expected: //')
            echo "[Leyden] INCOMPATIBLE"
            echo "  Expected: $EXPECTED"
            echo "  Current:  $JAVA_VER_FULL"
            echo "  Rebuild:  ./leyden-cache.sh build $JAR"
            exit 1
        else
            echo "[Leyden] OK — cache is compatible with: $JAVA_VER_FULL"
        fi
        ;;

    run)
        shift || true
        if [[ ! -f "$CACHE" ]]; then
            echo "[Leyden] No cache at $CACHE — running without cache" >&2
            exec "$JAVA_CMD" -jar "$JAR" "$@"
        fi
        exec "$JAVA_CMD" -XX:AOTCache="$CACHE" -jar "$JAR" "$@"
        ;;

    *)
        echo "Usage: $0 {build|run|check} [JAR] [args...]" >&2
        echo ""
        echo "  build  — create the AOT cache (2-phase training run)"
        echo "  run    — launch VATN CLI with the AOT cache"
        echo "  check  — verify cache compatibility with the current JVM"
        echo ""
        echo "Environment:"
        echo "  JAVA_CMD — override java binary (default: SDKMAN current, then PATH java)"
        exit 1
        ;;
esac
