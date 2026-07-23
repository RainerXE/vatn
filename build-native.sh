#!/usr/bin/env bash
# ==============================================================================
#  build-native.sh — Build native-image binaries for VATN CLI and Web Admin
#
#  Requires: GraalVM 25+ (with native-image), Maven 3.9+
#
#  Usage:
#    ./build-native.sh [options]
#
#  Options:
#    --install-dir DIR   Target directory  (default: ~/.vatn)
#    --cli-only          Only build vatn-cli
#    --webadmin-only     Only build vatn-webadmin
#    --no-install        Build but don't copy to install dir
#    --help              Show this help
#
#  Native binaries replace the shell-script launchers in the install dir,
#  giving sub-second cold start and lower memory usage.
# ==============================================================================
set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  RED='\033[0;31m' GRN='\033[0;32m' YLW='\033[1;33m'
  BLU='\033[0;34m' CYN='\033[0;36m' BLD='\033[1m' DIM='\033[2m' RST='\033[0m'
else
  RED='' GRN='' YLW='' BLU='' CYN='' BLD='' DIM='' RST=''
fi

info()  { printf "  ${CYN}▶${RST}  %s\n" "$*"; }
ok()    { printf "  ${GRN}✔${RST}  %s\n" "$*"; }
warn()  { printf "  ${YLW}⚠${RST}  %s\n" "$*"; }
err()   { printf "  ${RED}✖${RST}  %s\n" "$*" >&2; }
step()  { printf "\n${BLD}${BLU}━━  %s  ━━${RST}\n" "$*"; }
die()   { err "$*"; exit 1; }

ask() {
  printf "  ${CYN}?${RST}  %s " "$*"
  if [ -t 0 ]; then
    read -r REPLY
  elif [ -e /dev/tty ]; then
    read -r REPLY </dev/tty
  else
    REPLY=""
  fi
}

# ── Defaults ─────────────────────────────────────────────────────────────────
INSTALL_DIR="${VATN_HOME:-${VATN_INSTALL_DIR:-$HOME/.vatn}}"
BUILD_CLI=true
BUILD_WEBADMIN=true
DO_INSTALL=true

# ── Parse args ───────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --cli-only)    BUILD_WEBADMIN=false; shift ;;
    --webadmin-only) BUILD_CLI=false; shift ;;
    --no-install)  DO_INSTALL=false; shift ;;
    --help)        sed -n '3,16p' "$0"; exit 0 ;;
    *) die "Unknown option: $1";;
  esac
done

# ── Step 1: Check prerequisites ─────────────────────────────────────────────
step "Prerequisites"

JAVA_BIN=""
if command -v java &>/dev/null; then
  JAVA_BIN="$(command -v java)"
fi

if [ -z "$JAVA_BIN" ]; then
  die "Java not found. Install GraalVM 25+: sdk install java 25.0.2-graal"
fi

JAVA_ALL=$("$JAVA_BIN" -version 2>&1)
JAVA_RAW=$(echo "$JAVA_ALL" | head -1)
JAVA_MAJOR=$(printf '%s' "$JAVA_RAW" | grep -oE '[0-9]+' | head -1)
if [ "${JAVA_MAJOR:-0}" -lt 25 ] 2>/dev/null; then
  die "GraalVM 25+ required, found: $JAVA_RAW"
fi

if ! echo "$JAVA_ALL" | grep -qi "graalvm"; then
  warn "Not a GraalVM JDK: $JAVA_RAW"
  warn "Native-image may not be available."
fi

NATIVE_IMAGE=""
if command -v native-image &>/dev/null; then
  NATIVE_IMAGE="$(command -v native-image)"
elif [ -n "${GRAALVM_HOME:-}" ] && [ -f "$GRAALVM_HOME/bin/native-image" ]; then
  NATIVE_IMAGE="$GRAALVM_HOME/bin/native-image"
elif [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/bin/native-image" ]; then
  NATIVE_IMAGE="$JAVA_HOME/bin/native-image"
else
  # Derive from java binary location
  JAVA_DIR="$(dirname "$JAVA_BIN")"
  if [ -f "$JAVA_DIR/native-image" ]; then
    NATIVE_IMAGE="$JAVA_DIR/native-image"
  fi
fi

if [ -z "$NATIVE_IMAGE" ]; then
  die "native-image not found. Install GraalVM with native-image: sdk install java 25.0.2-graal"
fi
ok "native-image: $("$NATIVE_IMAGE" --version 2>&1 | head -1)"

if ! command -v mvn &>/dev/null; then
  die "Maven not found. Install Maven 3.9+."
fi
ok "Maven: $(mvn --version 2>&1 | head -1)"

# Set JAVA_HOME if not already set (required by native-maven-plugin)
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
  ok "JAVA_HOME set to: $JAVA_HOME"
fi

# ── Step 2: Locate source ────────────────────────────────────────────────────
step "Source code"

SRC_DIR=""
if [ -f "pom.xml" ] && grep "vatn-parent" pom.xml &>/dev/null; then
  SRC_DIR="$(pwd)"
  info "Using current directory: $SRC_DIR"
elif command -v git &>/dev/null; then
  ask "Clone vatn source from GitHub? [Y/n]:"
  REPLY="${REPLY:-y}"
  if [[ "${REPLY,,}" =~ ^n ]]; then
    die "Source required. Clone manually or re-run from a vatn clone."
  fi
  SRC_DIR="/tmp/vatn-build-$$"
  info "Cloning vatn (shallow)…"
  git clone --depth 1 "https://github.com/RainerXE/vatn.git" "$SRC_DIR" || {
    rm -rf "$SRC_DIR" 2>/dev/null
    die "Clone failed."
  }
  ok "Cloned to $SRC_DIR"
  cd "$SRC_DIR"
else
  die "git not found and pom.xml not found. Clone vatn first."
fi

cd "$SRC_DIR"

# ── Step 3: Build native images ─────────────────────────────────────────────
if $BUILD_CLI; then
  step "Building vatn-cli native binary"
  info "mvn package -Pnative -pl vatn-cli -am -DskipTests"
  mvn package -Pnative -pl vatn-cli -am -DskipTests 2>&1 | tail -5
  CLI_BINARY="$SRC_DIR/vatn-cli/target/vatn"
  if [ ! -f "$CLI_BINARY" ]; then
    die "CLI native binary not found at $CLI_BINARY"
  fi
  cli_size=$(ls -lh "$CLI_BINARY" | awk '{print $5}')
  ok "vatn-cli native binary built ($cli_size)"
fi

if $BUILD_WEBADMIN; then
  step "Building vatn-webadmin native binary"
  info "mvn package -Pnative -pl vatn-webadmin -am -DskipTests"
  mvn package -Pnative -pl vatn-webadmin -am -DskipTests 2>&1 | tail -5
  WEBADMIN_BINARY="$SRC_DIR/vatn-webadmin/target/vatn-webadmin"
  if [ ! -f "$WEBADMIN_BINARY" ]; then
    warn "Web admin native binary not found at $WEBADMIN_BINARY — skipping."
    BUILD_WEBADMIN=false
  else
    wa_size=$(ls -lh "$WEBADMIN_BINARY" | awk '{print $5}')
    ok "vatn-webadmin native binary built ($wa_size)"
  fi
fi

# ── Step 4: Install ─────────────────────────────────────────────────────────
if $DO_INSTALL; then
  step "Installing to $INSTALL_DIR/bin"

  if [ ! -d "$INSTALL_DIR/bin" ]; then
    warn "$INSTALL_DIR/bin does not exist — creating."
    mkdir -p "$INSTALL_DIR/bin"
  fi

  if $BUILD_CLI; then
    cp "$CLI_BINARY" "$INSTALL_DIR/bin/vatn"
    chmod +x "$INSTALL_DIR/bin/vatn"
    ok "vatn → $INSTALL_DIR/bin/vatn  ($cli_size native binary)"
  fi

  if $BUILD_WEBADMIN; then
    cp "$WEBADMIN_BINARY" "$INSTALL_DIR/bin/vatn-webadmin"
    chmod +x "$INSTALL_DIR/bin/vatn-webadmin"
    ok "vatn-webadmin → $INSTALL_DIR/bin/vatn-webadmin  ($wa_size native binary)"
  fi
fi

# ── Summary ──────────────────────────────────────────────────────────────────
printf "\n${BLD}${GRN}━━  Done  ━━${RST}\n\n"
if $BUILD_CLI; then
  printf "  ${BLD}CLI:${RST}        %s\n" "$INSTALL_DIR/bin/vatn"
fi
if $BUILD_WEBADMIN; then
  printf "  ${BLD}Web Admin:${RST}  %s\n" "$INSTALL_DIR/bin/vatn-webadmin"
fi
printf "\n"
printf "  Native cold start: ~24 ms (CLI), JVM mode: ~116 ms\n"
printf "\n"
if $DO_INSTALL; then
  printf "  Make sure ${CYN}%s/bin${RST} is on your PATH, or re-run the installer.\n" "$INSTALL_DIR"
fi
printf "\n"
