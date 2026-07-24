#!/usr/bin/env bash
set -euo pipefail

# vatn-dev-update — detect local repo, pull, rebuild, and reinstall VATN

: "${VATN_HOME:=${HOME}/.vatn}"
: "${VATN_SRC_DIR:=}"
: "${BRANCH:=main}"
: "${ORG:=RainerXE}"
: "${REPO:=vatn}"
: "${THREADS:=1C}"

RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[0;33m'; BLU='\033[0;34m'; DIM='\033[2m'; RST='\033[0m'
ok()  { printf "  ${GRN}✓${RST}  %s\n" "$1"; }
info(){ printf "  ${BLU}→${RST}  %s\n" "$1"; }
warn(){ printf "  ${YLW}⚠${RST}  %s\n" "$1"; }
die() { printf "  ${RED}✗${RST}  %s\n" "$1"; exit 1; }

step() { printf "\n${BLU}══ %s${RST}\n" "$1"; }

# ── thread count ──────────────────────────────────────────────────────────────
if [ -t 0 ] && [ -z "${THREADS:-}" ] && [ -z "${MAVEN_THREADS:-}" ]; then
  echo ""
  echo "  Build speed:"
  echo "    1) Fast — use all CPU cores (default)"
  echo "    2) Low load — 1 thread"
  read -r -t 10 REPLY || true
  case "${REPLY:-1}" in
    2|low|slow|l) THREADS="1" ;;
    *)             THREADS="1C" ;;
  esac
  echo ""
elif [ -z "${THREADS:-}" ]; then
  THREADS="1C"
fi
info "Build threads: $THREADS (set THREADS=1 for low load)"

# ── requirements ──────────────────────────────────────────────────────────────
command -v git >/dev/null 2>&1 || die "git is required"
command -v mvn >/dev/null 2>&1 || die "Maven (mvn) is required"
command -v java >/dev/null 2>&1 || die "Java is required"
command -v curl >/dev/null 2>&1 || warn "curl not found — GitHub API checks will be skipped"

# ── source directory ──────────────────────────────────────────────────────────
step "Source"

SRC_DIR=""
if [ -n "$VATN_SRC_DIR" ] && [ -f "$VATN_SRC_DIR/pom.xml" ]; then
  SRC_DIR="$VATN_SRC_DIR"
  info "Using VATN_SRC_DIR: $SRC_DIR"
elif [ -f "pom.xml" ] && grep -q "vatn-parent" pom.xml 2>/dev/null; then
  SRC_DIR="$(pwd)"
  info "Using current directory: $SRC_DIR"
else
  # Persistent cache clone — avoids full rebuilds on subsequent runs.
  # Does NOT touch ~/Development/vatn (your active workspace).
  SRC_DIR="$VATN_HOME/src"
  if [ -d "$SRC_DIR/.git" ]; then
    info "Using cached source at: $SRC_DIR"
  else
    info "Cloning ${ORG}/${REPO} (branch: ${BRANCH}) to $SRC_DIR …"
    git clone --depth 1 --branch "$BRANCH" "https://github.com/${ORG}/${REPO}.git" "$SRC_DIR" \
      || die "git clone failed"
    ok "Cloned to $SRC_DIR"
  fi
fi

cd "$SRC_DIR"

# ── git pull ──────────────────────────────────────────────────────────────────
if [ -d ".git" ]; then
  step "Update"
  info "Pulling latest changes …"
  git pull --ff-only || warn "git pull failed — continuing with local state"
  CURRENT_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
  info "At commit: $CURRENT_HASH"
fi

# ── detect changed modules ────────────────────────────────────────────────────
step "Build"

HAS_WEBADMIN=false
if [ -f "$VATN_HOME/lib/vatn-webadmin.jar" ] || [ -f "$VATN_HOME/bin/vatn-webadmin" ]; then HAS_WEBADMIN=true; fi

INSTALLED_PLUGINS=()
if [ -d "$VATN_HOME/plugins" ]; then
  for jar in "$VATN_HOME/plugins"/vatn-plugin-*.jar; do
    [ -f "$jar" ] || continue
    name=$(basename "$jar" .jar)
    INSTALLED_PLUGINS+=("$name")
  done
fi

# Fresh clone detection: build everything if no build artifacts exist yet
if [ ! -d vatn-cli/target ] && [ -z "$(git diff --name-only HEAD 2>/dev/null)" ]; then
  info "Fresh clone — building all modules"
  BUILD_MODULES="vatn-cli"
  if [ "$HAS_WEBADMIN" = true ]; then BUILD_MODULES="$BUILD_MODULES,vatn-webadmin"; fi
  mvn package -T "$THREADS" -pl "$BUILD_MODULES" -am -DskipTests -q || die "Build failed"
  for plugin in "${INSTALLED_PLUGINS[@]}"; do
    mvn package -T "$THREADS" -pl "plugins/$plugin" -am -DskipTests -q 2>/dev/null || true
  done
  ok "Build complete"
else
  # Determine diff base — use ORIG_HEAD if available, fall back to reflog
  DIFF_BASE=""
  if git rev-parse --verify ORIG_HEAD >/dev/null 2>&1; then
    DIFF_BASE="ORIG_HEAD"
  elif git rev-parse --verify "HEAD@{1}" >/dev/null 2>&1; then
    DIFF_BASE="HEAD@{1}"
  fi
  CHANGED=""
  if [ -n "$DIFF_BASE" ]; then
    CHANGED=$(git diff --name-only "$DIFF_BASE" HEAD 2>/dev/null || true)
  fi
  CHANGED=$( { echo "$CHANGED"; git diff --name-only HEAD 2>/dev/null; } | sort -u | grep -E '(\.java$|\.xml$|\.properties$)' | grep -v '/test/' || true)

  if [ -z "$CHANGED" ]; then
    info "No source changes — nothing to build."
  elif echo "$CHANGED" | grep -qE '^(vatn-api/|vatn-core/|pom\.xml$)'; then
    BUILD_MODULES="vatn-cli"
    if [ "$HAS_WEBADMIN" = true ]; then BUILD_MODULES="$BUILD_MODULES,vatn-webadmin"; fi
    info "vatn-api or vatn-core changed — rebuilding all modules"
    mvn package -T "$THREADS" -pl "$BUILD_MODULES" -am -DskipTests -q || die "Build failed"
    for plugin in "${INSTALLED_PLUGINS[@]}"; do
      mvn package -T "$THREADS" -pl "plugins/$plugin" -am -DskipTests -q 2>/dev/null || true
    done
    ok "Build complete"
  else
    BUILD_MODULES=""
    echo "$CHANGED" | grep '^vatn-cli/' >/dev/null && BUILD_MODULES="$BUILD_MODULES,vatn-cli"
    if [ "$HAS_WEBADMIN" = true ] && echo "$CHANGED" | grep -q '^vatn-webadmin/'; then
      BUILD_MODULES="$BUILD_MODULES,vatn-webadmin"
    fi
    for plugin in "${INSTALLED_PLUGINS[@]}"; do
      if echo "$CHANGED" | grep "^plugins/$plugin/" >/dev/null; then
        BUILD_MODULES="$BUILD_MODULES,plugins/$plugin"
        if [ "$HAS_WEBADMIN" = true ]; then
          BUILD_MODULES="$BUILD_MODULES,vatn-webadmin"
        fi
      fi
    done
    BUILD_MODULES="${BUILD_MODULES#,}"
    if [ -n "$BUILD_MODULES" ]; then
      info "Building: $BUILD_MODULES"
  mvn package -T "$THREADS" -pl "$BUILD_MODULES" -am -DskipTests -q || die "Build failed"
      ok "Build complete"
    else
      info "Only non-source files changed — nothing to build."
    fi
  fi
fi

# ── install ───────────────────────────────────────────────────────────────────
step "Install"
set +e

mkdir -p "$VATN_HOME"/{lib,plugins,bin}

CLI_JAR=$(find vatn-cli/target -maxdepth 1 -name "*.jar" -not -name "original-*" 2>/dev/null | head -1)
if [ -n "$CLI_JAR" ]; then
  cp "$CLI_JAR" "$VATN_HOME/lib/vatn-cli.jar"
  ok "vatn-cli.jar"
fi

if [ "$HAS_WEBADMIN" = true ]; then
  WEB_JAR=$(find vatn-webadmin/target -maxdepth 1 -name "*.jar" -not -name "original-*" 2>/dev/null | head -1)
  [ -n "$WEB_JAR" ] && cp "$WEB_JAR" "$VATN_HOME/lib/vatn-webadmin.jar" && ok "vatn-webadmin.jar"
fi

for plugin in "${INSTALLED_PLUGINS[@]}"; do
  JAR=$(find "plugins/$plugin/target" -maxdepth 1 -name "*.jar" -not -name "original-*" 2>/dev/null | head -1)
  [ -n "$JAR" ] && cp "$JAR" "$VATN_HOME/plugins/$plugin.jar" && ok "$plugin.jar"
done

# ── done ──────────────────────────────────────────────────────────────────────
printf "\n${GRN}══ Done${RST}\n"
info "VATN_HOME: $VATN_HOME"
info "Restart any running services:  vatn webadmin restart"
echo ""
