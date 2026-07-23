#!/usr/bin/env bash
set -euo pipefail

# vatn-dev-update — detect local repo, pull, rebuild, and reinstall VATN

: "${VATN_HOME:=${HOME}/.vatn}"
: "${VATN_SRC_DIR:=}"
: "${BRANCH:=main}"
: "${ORG:=RainerXE}"
: "${REPO:=vatn}"

RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[0;33m'; BLU='\033[0;34m'; DIM='\033[2m'; RST='\033[0m'
ok()  { printf "  ${GRN}✓${RST}  %s\n" "$1"; }
info(){ printf "  ${BLU}→${RST}  %s\n" "$1"; }
warn(){ printf "  ${YLW}⚠${RST}  %s\n" "$1"; }
die() { printf "  ${RED}✗${RST}  %s\n" "$1"; exit 1; }

step() { printf "\n${BLU}══ %s${RST}\n" "$1"; }

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
  SRC_DIR="/tmp/vatn-dev-update-$$"
  info "Cloning ${ORG}/${REPO} (branch: ${BRANCH}) …"
  git clone --depth 1 --branch "$BRANCH" "https://github.com/${ORG}/${REPO}.git" "$SRC_DIR" \
    || die "git clone failed"
  ok "Cloned to $SRC_DIR"
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

# ── build ─────────────────────────────────────────────────────────────────────
step "Build"

# Detect which parts are installed
HAS_WEBADMIN=false
[ -f "$VATN_HOME/lib/vatn-webadmin.jar" ] || [ -f "$VATN_HOME/bin/vatn-webadmin" ] && HAS_WEBADMIN=true

BUILD_MODULES="vatn-cli"
$HAS_WEBADMIN && BUILD_MODULES="$BUILD_MODULES,vatn-webadmin"

info "Building: $BUILD_MODULES"
# Only build vatn-cli + webadmin centrally; plugins built individually below
if ! mvn package -pl "$BUILD_MODULES" -am -DskipTests -q; then
  die "Build failed for modules: $BUILD_MODULES"
fi
ok "Modules built: $BUILD_MODULES"

INSTALLED_PLUGINS=()
if [ -d "$VATN_HOME/plugins" ]; then
  for jar in "$VATN_HOME/plugins"/vatn-plugin-*.jar; do
    [ -f "$jar" ] || continue
    name=$(basename "$jar" .jar)
    INSTALLED_PLUGINS+=("$name")
  done
fi

if [ ${#INSTALLED_PLUGINS[@]} -gt 0 ]; then
  info "Rebuilding ${#INSTALLED_PLUGINS[@]} installed plugin(s) …"
  for plugin in "${INSTALLED_PLUGINS[@]}"; do
    printf "  ${DIM}%s${RST} … " "$plugin"
    if mvn package -pl "plugins/$plugin" -am -DskipTests -q 2>/dev/null; then
      printf "${GRN}OK${RST}\n"
    else
      printf "${RED}failed${RST}\n"
    fi
  done
fi

# ── install ───────────────────────────────────────────────────────────────────
step "Install"

mkdir -p "$VATN_HOME"/{lib,plugins,bin}

# CLI JAR
CLI_JAR=$(find vatn-cli/target -maxdepth 1 -name "*.jar" -not -name "original-*" 2>/dev/null | head -1)
if [ -n "$CLI_JAR" ]; then
  cp "$CLI_JAR" "$VATN_HOME/lib/vatn-cli.jar"
  ok "vatn-cli.jar installed"
else
  warn "vatn-cli.jar not found in target/"
fi

# WebAdmin JAR
if $HAS_WEBADMIN; then
  WEB_JAR=$(find vatn-webadmin/target -maxdepth 1 -name "*.jar" -not -name "original-*" 2>/dev/null | head -1)
  if [ -n "$WEB_JAR" ]; then
    cp "$WEB_JAR" "$VATN_HOME/lib/vatn-webadmin.jar"
    ok "vatn-webadmin.jar installed"
  fi
fi

# Plugins
for plugin in "${INSTALLED_PLUGINS[@]}"; do
  PLUGIN_JAR=$(find "plugins/$plugin/target" -maxdepth 1 -name "*.jar" -not -name "original-*" 2>/dev/null | head -1)
  if [ -n "$PLUGIN_JAR" ]; then
    cp "$PLUGIN_JAR" "$VATN_HOME/plugins/$plugin.jar"
    ok "$plugin.jar installed"
  fi
done

# ── done ──────────────────────────────────────────────────────────────────────
printf "\n${GRN}══ Done${RST}\n"
info "VATN_HOME: $VATN_HOME"
info "Restart any running services:  vatn webadmin restart"
echo ""
