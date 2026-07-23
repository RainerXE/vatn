#!/usr/bin/env bash
# ==============================================================================
#  VATN — Runtime for Personal Services  ·  Installer
#
#  One-liner (interactive):
#    curl -fsSL https://raw.githubusercontent.com/RainerXE/vatn/main/install.sh | bash
#
#  Non-interactive (CI / pipe):
#    VATN_COMPONENTS=all bash <(curl -fsSL .../install.sh)
#
#  Env-var overrides:
#    VATN_INSTALL_DIR    — target directory               (default: ~/.vatn)
#    VATN_COMPONENTS     — "all" | comma-list of: core,webadmin,plugins,examples
#                          default: all components enabled
#    VATN_PLUGINS        — comma-list / "all" / "recommended"  (default: recommended)
# ==============================================================================
set -euo pipefail

# ── GitHub coordinates ────────────────────────────────────────────────────────
VATN_ORG="RainerXE"
VATN_REPO="vatn"
MIN_JAVA_MAJOR=25

# ── terminal output ───────────────────────────────────────────────────────────
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  RED='\033[0;31m' GRN='\033[0;32m' YLW='\033[1;33m'
  BLU='\033[0;34m' CYN='\033[0;36m' BLD='\033[1m' DIM='\033[2m' RST='\033[0m'
else
  RED='' GRN='' YLW='' BLU='' CYN='' BLD='' DIM='' RST=''
fi

info()  { printf "  ${CYN}▶${RST}  %s\n"          "$*"; }
ok()    { printf "  ${GRN}✔${RST}  %s\n"          "$*"; }
warn()  { printf "  ${YLW}⚠${RST}  %s\n"          "$*"; }
err()   { printf "  ${RED}✖${RST}  %s\n" "$*" >&2; }
step()  { printf "\n${BLD}${BLU}━━  %s  ━━${RST}\n" "$*"; }
die()   { err "$*"; exit 1; }

# ── Install log ───────────────────────────────────────────────────────────────
# We set up the log dir early (best-effort) and tee all stdout+stderr into it.
# The log path is also recorded in itself so it's self-describing.
VATN_LOG_DIR="${VATN_INSTALL_DIR:-$HOME/.vatn}/logs"
mkdir -p "$VATN_LOG_DIR" 2>/dev/null || VATN_LOG_DIR="/tmp"
INSTALL_LOG="$VATN_LOG_DIR/install-$(date +%Y%m%d-%H%M%S).log"
# Tee all stdout+stderr to the log file without stripping ANSI codes from the terminal,
# but write a clean (no-color) copy to the log file.
exec > >(tee >(sed 's/\x1B\[[0-9;]*[mK]//g' >> "$INSTALL_LOG")) 2>&1

# Structured key=value log entries — append to the install log
log_entry() { printf "%-30s %s\n" "$1" "$2" >> "$INSTALL_LOG"; }

# Manifest array — filled in as we install things
MANIFEST=()

# read from /dev/tty so the installer works when piped via curl | bash
ask() {
  printf "  ${CYN}?${RST}  %s " "$*"
  if [ -t 0 ]; then
    read -r REPLY
  elif [ -e /dev/tty ]; then
    read -r REPLY </dev/tty
  else
    REPLY=""
    printf "(non-interactive — using default)\n"
  fi
}

yn() {
  # yn "Question" [default_y|default_n] → sets REPLY to y or n
  local q="$1" def="${2:-y}"
  local hint
  [ "$def" = "y" ] && hint="[Y/n]" || hint="[y/N]"
  ask "$q $hint:"
  REPLY="${REPLY:-$def}"
  [[ "${REPLY,,}" =~ ^y ]] && REPLY="y" || REPLY="n"
}

# ── Build from source (fallback for missing release assets) ──────────────────
# Usage: build_artifact "Description" <mvn-module> <target-path> [native-binary-name]
build_artifact() {
  local desc="$1" module="$2" target="$3" native_name="${4:-}"
  local src_dir

  if ! command -v mvn &>/dev/null; then
    warn "Maven not found — cannot build $desc from source."
    return 1
  fi

  # Detect or clone source
  src_dir=""
  if [ -f "pom.xml" ] && grep "vatn-parent" pom.xml &>/dev/null; then
    src_dir="$(pwd)"
  elif [ -n "${VATN_SRC_DIR:-}" ] && [ -f "$VATN_SRC_DIR/pom.xml" ]; then
    src_dir="$VATN_SRC_DIR"
  elif command -v git &>/dev/null && ( [ -t 0 ] || [ -e /dev/tty ] ); then
    ask "Clone vatn source to build $desc? [Y/n]:"
    REPLY="${REPLY:-y}"
    if [[ "${REPLY,,}" =~ ^n ]]; then
      warn "Skipping $desc."
      return 1
    fi
    src_dir="/tmp/vatn-build-$$"
    info "Cloning vatn (shallow)…"
    git clone --depth 1 "https://github.com/${VATN_ORG}/${VATN_REPO}.git" "$src_dir" &>/dev/null || {
      warn "Clone failed."
      rm -rf "$src_dir" 2>/dev/null
      return 1
    }
  else
    warn "Source not found and cannot clone — cannot build $desc."
    return 1
  fi

  info "Building $desc (mvn package -pl $module -am -DskipTests)…"
  if ! (cd "$src_dir" && mvn package -pl "$module" -am -DskipTests -q &>/dev/null); then
    warn "Build failed for $desc."
    return 1
  fi

  local built=""
  if [ -n "$native_name" ]; then
    built=$(find "$src_dir/$module/target" -maxdepth 1 -type f -name "$native_name" 2>/dev/null | head -1)
  else
    built=$(find "$src_dir/$module/target" -maxdepth 1 -name "*.jar" -not -name "original-*" 2>/dev/null | head -1)
  fi

  if [ -n "$built" ]; then
    cp "$built" "$target"
    [ -x "$built" ] && chmod +x "$target"
    ok "$desc built and installed"
    return 0
  else
    warn "Build succeeded but artifact not found in target/"
    return 1
  fi
}

# ── banner ────────────────────────────────────────────────────────────────────
printf "\n${BLD}${BLU}"
printf "  ╔══════════════════════════════════════════════════════╗\n"
printf "  ║                                                      ║\n"
printf "  ║   ⬡  VATN — Runtime for Personal Services           ║\n"
printf "  ║      Installer v1.0                                  ║\n"
printf "  ║                                                      ║\n"
printf "  ╚══════════════════════════════════════════════════════╝\n"
printf "${RST}\n"

# ── OS / arch ─────────────────────────────────────────────────────────────────
step "Checking environment"

OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS" in
  Linux*)  PLATFORM="linux" ;;
  Darwin*) PLATFORM="macos" ;;
  *) die "Unsupported OS: $OS — use install.ps1 on Windows." ;;
esac
info "Platform: $PLATFORM ($ARCH)"

for cmd in curl unzip; do
  command -v "$cmd" &>/dev/null || die "Required tool not found: $cmd. Install it and retry."
done

# ── Component selection ───────────────────────────────────────────────────────
step "Component selection"

INSTALL_CORE=true
INSTALL_WEBADMIN=true
INSTALL_PLUGINS=true
INSTALL_EXAMPLES=true

if [ -n "${VATN_COMPONENTS:-}" ]; then
  # parse env-var override
  case "${VATN_COMPONENTS,,}" in
    all) ;; # keep all true
    *)
      INSTALL_CORE=false INSTALL_WEBADMIN=false INSTALL_PLUGINS=false INSTALL_EXAMPLES=false
      for token in $(echo "$VATN_COMPONENTS" | tr ',' ' '); do
        case "$token" in
          core)     INSTALL_CORE=true ;;
          webadmin) INSTALL_WEBADMIN=true ;;
          plugins)  INSTALL_PLUGINS=true ;;
          examples) INSTALL_EXAMPLES=true ;;
        esac
      done
      ;;
  esac
else
  printf "\n"
  printf "  ${BLD}Which components would you like to install?${RST}\n"
  printf "  ${DIM}All components are recommended (and enabled by default).${RST}\n\n"

  printf "  ${BLD}[1] VATN Core Runtime${RST} — CLI, node engine, DAG, queues, scheduler\n"
  yn "    Install VATN Core?" "y"; [ "$REPLY" = "y" ] && INSTALL_CORE=true || INSTALL_CORE=false

  printf "\n  ${BLD}[2] VATN Web Admin${RST} — browser-based admin & container GUI (background service)\n"
  printf "      ${DIM}Installs as a launchd / systemd daemon, starts automatically${RST}\n"
  yn "    Install VATN Web Admin?" "y"; [ "$REPLY" = "y" ] && INSTALL_WEBADMIN=true || INSTALL_WEBADMIN=false

  printf "\n  ${BLD}[3] Plugins${RST} — auth, CORS, Swagger, metrics, postgres, redis, WASM, and more\n"
  yn "    Install Plugins?" "y"; [ "$REPLY" = "y" ] && INSTALL_PLUGINS=true || INSTALL_PLUGINS=false

  printf "\n  ${BLD}[4] Examples${RST} — runnable Maven projects showing core VATN concepts\n"
  yn "    Clone Examples?" "y"; [ "$REPLY" = "y" ] && INSTALL_EXAMPLES=true || INSTALL_EXAMPLES=false
fi

printf "\n"
$INSTALL_CORE     && ok "Core Runtime   ✓" || info "Core Runtime   skipped"
$INSTALL_WEBADMIN && ok "Web Admin      ✓" || info "Web Admin      skipped"
$INSTALL_PLUGINS  && ok "Plugins        ✓" || info "Plugins        skipped"
$INSTALL_EXAMPLES && ok "Examples       ✓" || info "Examples       skipped"

# ── Java detection ────────────────────────────────────────────────────────────
step "Java"

JAVA_OK=false
JAVA_RAW=""
if command -v java &>/dev/null; then
  JAVA_RAW=$(java -version 2>&1 | head -1)
  JAVA_MAJOR=$(printf '%s' "$JAVA_RAW" | grep -oE '[0-9]+' | head -1)
  if [ "${JAVA_MAJOR:-0}" -ge "$MIN_JAVA_MAJOR" ] 2>/dev/null; then
    JAVA_OK=true
    ok "Found compatible Java: $JAVA_RAW"
  else
    warn "Java $JAVA_MAJOR found — VATN requires Java $MIN_JAVA_MAJOR+."
  fi
else
  warn "No Java installation found."
fi

if ! $JAVA_OK; then
  printf "\n"
  info "VATN requires Java $MIN_JAVA_MAJOR+ (GraalVM 25 recommended)."
  printf "\n"
  printf "  Install it with SDKMAN:\n"
  printf "    ${BLD}sdk install java 25.0.2-graal${RST}\n"
  printf "    ${BLD}sdk default java 25.0.2-graal${RST}\n"
  printf "\n"
  printf "  Get SDKMAN: ${CYN}https://sdkman.io${RST}\n"
  printf "\n"
  die "Install Java $MIN_JAVA_MAJOR+ and re-run this installer."
fi

ok "Using: $JAVA_RAW"

# ── Installation directory ────────────────────────────────────────────────────
step "Installation directory"

DEFAULT_DIR="${VATN_INSTALL_DIR:-$HOME/.vatn}"
printf "\n"
ask "Install location [$DEFAULT_DIR]:"
INSTALL_DIR="${REPLY:-$DEFAULT_DIR}"
INSTALL_DIR="${INSTALL_DIR/#\~/$HOME}"

if [ -d "$INSTALL_DIR/lib" ] || [ -d "$INSTALL_DIR/plugins" ]; then
  warn "Existing installation found at $INSTALL_DIR."
  ask "Upgrade in place? [Y/n]:"
  [[ "${REPLY:-y}" =~ ^[Nn]$ ]] && die "Installation cancelled."
fi

mkdir -p "$INSTALL_DIR"/{bin,lib,plugins,config,logs}
ok "Directory layout ready: $INSTALL_DIR"

# ── Latest release tag ───────────────────────────────────────────────────────
LATEST_TAG=$(curl -fsSL \
  "https://api.github.com/repos/${VATN_ORG}/${VATN_REPO}/releases/latest" \
  2>/dev/null | grep '"tag_name"' | cut -d'"' -f4 || true)
LATEST_TAG="${LATEST_TAG:-latest}"

# ── [1] Download VATN Core ───────────────────────────────────────────────────
if $INSTALL_CORE; then
  step "Installing VATN Core Runtime"
  info "Release tag: $LATEST_TAG"

  CORE_URL="https://github.com/${VATN_ORG}/${VATN_REPO}/releases/download/${LATEST_TAG}/vatn-cli.jar"
  if curl -fsSL --progress-bar -o "$INSTALL_DIR/lib/vatn-cli.jar" "$CORE_URL" 2>/dev/null; then
    ok "vatn-cli.jar downloaded"
  else
    warn "vatn-cli.jar not on release yet."
    build_artifact "VATN CLI" "vatn-cli" "$INSTALL_DIR/lib/vatn-cli.jar" || {
      echo "# placeholder" >"$INSTALL_DIR/lib/vatn-cli.jar.placeholder"
    }
  fi

  # Launcher
  LAUNCHER="$INSTALL_DIR/bin/vatn"
  cat >"$LAUNCHER" <<LAUNCHER_EOF
#!/usr/bin/env bash
# VATN launcher — https://github.com/RainerXE/vatn
VATN_HOME="\${VATN_HOME:-${INSTALL_DIR}}"
VATN_JAR="\$VATN_HOME/lib/vatn-cli.jar"
VATN_PLUGINS_DIR="\$VATN_HOME/plugins"
VATN_CONF="\$VATN_HOME/config/vatn.conf"

if [ ! -f "\$VATN_JAR" ]; then
  echo "vatn-cli.jar not found at \$VATN_JAR" >&2
  echo "Build from source or download a release, then copy to \$VATN_HOME/lib/" >&2
  exit 1
fi

CP="\$VATN_JAR"
for jar in "\$VATN_PLUGINS_DIR"/*.jar; do
  [ -f "\$jar" ] && CP="\$CP:\$jar"
done

exec java \\
  -cp "\$CP" \\
  -Dvatn.home="\$VATN_HOME" \\
  -Dvatn.config="\$VATN_CONF" \\
  -Dvatn.plugins.dir="\$VATN_PLUGINS_DIR" \\
  dev.vatn.cli.VatnCLI "\$@"
LAUNCHER_EOF
  chmod +x "$LAUNCHER"
  ok "Launcher: $LAUNCHER"

  # Config
  CONF="$INSTALL_DIR/config/vatn.conf"
  if [ ! -f "$CONF" ]; then
    cat >"$CONF" <<'CONF_EOF'
# VATN Node configuration
# Docs: https://github.com/RainerXE/vatn/blob/main/docs/configuration.md

# vatn.nodeId=my-node-1   # auto-generated if omitted
vatn.port=8080
vatn.host=0.0.0.0

# Admin UI — set VATN_ADMIN_TOKEN env var or uncomment below
# vatn.admin.token=change-me

vatn.log.level=INFO
CONF_EOF
    ok "Config created: $CONF"
  else
    info "Existing config preserved: $CONF"
  fi
fi

# ── [2] Install VATN Web Admin ────────────────────────────────────────────────
if $INSTALL_WEBADMIN; then
  step "Installing VATN Web Admin"

  WEBADMIN_BIN="$INSTALL_DIR/bin/vatn-webadmin"
  WEBADMIN_URL="https://github.com/${VATN_ORG}/${VATN_REPO}/releases/download/${LATEST_TAG}/vatn-webadmin-${PLATFORM}-$(uname -m)"
  WEBADMIN_JAR_URL="https://github.com/${VATN_ORG}/${VATN_REPO}/releases/download/${LATEST_TAG}/vatn-webadmin.jar"

  if curl -fsSL --progress-bar -o "$WEBADMIN_BIN" "$WEBADMIN_URL" 2>/dev/null; then
    chmod +x "$WEBADMIN_BIN"
    ok "vatn-webadmin native binary installed"
  elif curl -fsSL --progress-bar -o "$INSTALL_DIR/lib/vatn-webadmin.jar" "$WEBADMIN_JAR_URL" 2>/dev/null; then
    # fat-jar fallback — wrap in a launcher script
    cat >"$WEBADMIN_BIN" <<WEBADMIN_EOF
#!/usr/bin/env bash
exec java -jar "${INSTALL_DIR}/lib/vatn-webadmin.jar" "\$@"
WEBADMIN_EOF
    chmod +x "$WEBADMIN_BIN"
    ok "vatn-webadmin jar installed (JVM mode)"
  else
    warn "vatn-webadmin not on release yet."
    build_artifact "vatn-webadmin (JAR)" "vatn-webadmin" "$INSTALL_DIR/lib/vatn-webadmin.jar" || true
  fi

  # Register as background daemon
  if [ "$PLATFORM" = "macos" ]; then
    PLIST_LABEL="dev.vatn.webadmin"
    PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"
    mkdir -p "$INSTALL_DIR/logs"
    cat >"$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${PLIST_LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${WEBADMIN_BIN}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${INSTALL_DIR}/logs/webadmin.out.log</string>
    <key>StandardErrorPath</key>
    <string>${INSTALL_DIR}/logs/webadmin.err.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>VATN_ADMIN_USER</key>
        <string>admin</string>
    </dict>
</dict>
</plist>
EOF
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    if [ -f "$WEBADMIN_BIN" ]; then
      launchctl load "$PLIST_PATH"
      ok "LaunchAgent registered — Web Admin starts automatically on login"
    else
      warn "LaunchAgent written to $PLIST_PATH but binary not found — load it manually once built"
    fi

  elif [ "$PLATFORM" = "linux" ]; then
    SYSTEMD_DIR="$HOME/.config/systemd/user"
    mkdir -p "$SYSTEMD_DIR" "$INSTALL_DIR/logs"
    SERVICE_PATH="$SYSTEMD_DIR/vatn-webadmin.service"
    cat >"$SERVICE_PATH" <<EOF
[Unit]
Description=VATN Web Admin
Documentation=https://github.com/RainerXE/vatn
After=network.target

[Service]
ExecStart=${WEBADMIN_BIN}
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=vatn-webadmin

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    if [ -f "$WEBADMIN_BIN" ]; then
      systemctl --user enable vatn-webadmin.service
      systemctl --user restart vatn-webadmin.service
      ok "Systemd user service enabled and started"
    else
      warn "Service file written to $SERVICE_PATH but binary not found — enable it manually once built"
    fi
  fi
fi

# ── [3] Plugin selection & download ─────────────────────────────────────────
if $INSTALL_PLUGINS; then
  step "Plugin selection"

  # name | description | default?
  PLUGINS=(
    "cors       |CORS filter for browser-accessible APIs                     |yes"
    "auth       |JWT + API-key authentication                                |yes"
    "swagger    |OpenAPI / Swagger UI at /api/docs                           |yes"
    "security   |CSRF protection, rate limiting, security headers            |no"
    "bcrypt     |BCrypt password hashing service                             |no"
    "postgres   |PostgreSQL connection pool (HikariCP)                       |no"
    "redis      |Redis client (Jedis)                                        |no"
    "mongodb    |MongoDB driver integration                                   |no"
    "openai     |OpenAI / Claude / local LLM client                          |no"
    "metrics    |Prometheus /metrics endpoint (Micrometer)                   |no"
    "email      |SMTP email via Jakarta Mail                                  |no"
    "slack      |Slack webhook + Events API                                   |no"
    "s3         |AWS S3 / compatible object storage                           |no"
    "wasm       |WebAssembly (WASM) module execution via Chicory              |no"
    "devenv     |Developer environment scanner (runtimes, LLMs, containers)  |no"
    "containers |Container GUI — Docker/Podman/Distrobox management          |no"
    "comm       |Communication hub: Telegram, Signal, RCS                    |no"
    "fts        |Full-text search (SQLite FTS5, BM25, snippets)              |no"
    "activitypub|ActivityPub / Fediverse federation                           |no"
  )

  if [ -z "${VATN_PLUGINS:-}" ]; then
    printf "\n"
    printf "  ${BLD}Select plugins to install:${RST}\n"
    printf "  ${DIM}admin is always included.  ${YLW}[*]${DIM} = recommended default.${RST}\n\n"
    printf "  ${GRN}[always]${RST}  %-14s  Admin dashboard + JVM metrics + workload monitor\n" "vatn-plugin-admin"
    printf "\n"

    IDX=1
    for entry in "${PLUGINS[@]}"; do
      IFS='|' read -r PNAME PDESC PDEF <<< "$entry"
      PNAME="${PNAME// /}"; PDEF="${PDEF// /}"
      MARKER="     "
      [ "$PDEF" = "yes" ] && MARKER="${YLW}[*]${RST}  "
      printf "  %s${BLD}%2d)${RST}  %-20s  %s\n" "$MARKER" "$IDX" "vatn-plugin-$PNAME" "$PDESC"
      IDX=$((IDX + 1))
    done

    printf "\n"
    printf "  ${DIM}Enter numbers (e.g. 1 3 6), 'recommended', or 'all'.  Default: recommended${RST}\n"
    ask "Your selection:"
    VATN_PLUGINS="${REPLY:-recommended}"
  fi

  SELECTED_PLUGINS=("admin")
  case "${VATN_PLUGINS,,}" in
    all)
      for entry in "${PLUGINS[@]}"; do
        IFS='|' read -r PNAME _ _ <<< "$entry"
        SELECTED_PLUGINS+=("${PNAME// /}")
      done
      ;;
    recommended|"")
      for entry in "${PLUGINS[@]}"; do
        IFS='|' read -r PNAME _ PDEF <<< "$entry"
        [ "${PDEF// /}" = "yes" ] && SELECTED_PLUGINS+=("${PNAME// /}")
      done
      ;;
    *)
      IDX=1
      for entry in "${PLUGINS[@]}"; do
        IFS='|' read -r PNAME _ _ <<< "$entry"
        for token in $(echo "$VATN_PLUGINS" | tr ',' ' '); do
          if [ "$token" = "$IDX" ] || [ "$token" = "${PNAME// /}" ]; then
            SELECTED_PLUGINS+=("${PNAME// /}"); break
          fi
        done
        IDX=$((IDX + 1))
      done
      ;;
  esac

  step "Downloading plugins"
  FAILED=()
  for plugin in "${SELECTED_PLUGINS[@]}"; do
    jar="vatn-plugin-${plugin}.jar"
    url="https://github.com/${VATN_ORG}/${VATN_REPO}/releases/download/${LATEST_TAG}/${jar}"
    info "Fetching $jar…"
    if curl -fsSL --progress-bar -o "$INSTALL_DIR/plugins/$jar" "$url" 2>/dev/null; then
      ok "$jar"
    else
      warn "Not on release: $jar"
      rm -f "$INSTALL_DIR/plugins/$jar"
      FAILED+=("$plugin")
    fi
  done
  if [ ${#FAILED[@]} -gt 0 ]; then
    printf "\n"
    yn "Build missing plugins from source?" "y"
    if [ "$REPLY" = "y" ]; then
      for plugin in "${FAILED[@]}"; do
        build_artifact "vatn-plugin-$plugin" "plugins/vatn-plugin-$plugin" "$INSTALL_DIR/plugins/vatn-plugin-$plugin.jar" || true
      done
    fi
  fi
fi

# ── [4] Examples ──────────────────────────────────────────────────────────────
if $INSTALL_EXAMPLES; then
  step "Examples"

  if ! command -v git &>/dev/null; then
    warn "git not found — skipping example clone."
  else
    DEFAULT_DEV=""
    for candidate in "$HOME/Development" "$HOME/Projects" "$HOME/dev" "$HOME/code" "$HOME/src"; do
      [ -d "$candidate" ] && { DEFAULT_DEV="$candidate"; break; }
    done
    [ -z "$DEFAULT_DEV" ] && DEFAULT_DEV="$HOME/Development"

    ask "Clone examples to (accept default with Enter) [$DEFAULT_DEV]:"
    DEV_DIR="$DEFAULT_DEV"
    if [ -n "${REPLY:-}" ] && [ "$REPLY" != "y" ]; then
      DEV_DIR="$REPLY"
    fi
    DEV_DIR="${DEV_DIR/#\~/$HOME}"
    mkdir -p "$DEV_DIR"

    TARGET="$DEV_DIR/vatn"
    if [ -d "$TARGET/.git" ]; then
      info "vatn already cloned — pulling latest…"
      git -C "$TARGET" pull --ff-only 2>/dev/null && ok "vatn up to date" || warn "Could not update"
    else
      info "Cloning vatn…"
      git clone "https://github.com/$VATN_ORG/$VATN_REPO.git" "$TARGET" \
        && ok "vatn → $TARGET" \
        || warn "Clone failed (check network/permissions)"
    fi

    if [ -d "$TARGET/examples" ]; then
      printf "\n"
      printf "  ${BLD}Available examples:${RST}\n"
      printf "  ${DIM}Run any example: cd %s/examples/<N>-<name> && mvn package -DskipTests && java -jar target/*.jar${RST}\n\n" "$TARGET"
      ls "$TARGET/examples/" 2>/dev/null | grep -v pom.xml | grep -v README | sort | \
        while IFS= read -r d; do printf "    ${CYN}%-30s${RST}\n" "$d"; done
      printf "\n"
      ok "Examples ready in $TARGET/examples/"
    fi
  fi
fi

# ── Developer source setup (optional) ────────────────────────────────────────
if $INSTALL_CORE; then
  step "Developer setup (optional)"

  printf "\n"
  printf "  Clone source to build custom plugins, contribute, or run local builds.\n"
  printf "  Requires: git, Maven 3.9+\n\n"

  yn "Clone source repo for local development?" "n"
  if [ "$REPLY" = "y" ]; then
    if ! command -v git &>/dev/null; then
      warn "git not found — skipping."
    else
      DEFAULT_DEV=""
      for candidate in "$HOME/Development" "$HOME/Projects" "$HOME/dev" "$HOME/code"; do
        [ -d "$candidate" ] && { DEFAULT_DEV="$candidate"; break; }
      done
      [ -z "$DEFAULT_DEV" ] && DEFAULT_DEV="$HOME/Development"

      ask "Development directory (accept default with Enter) [$DEFAULT_DEV]:"
      DEV_DIR="$DEFAULT_DEV"
      if [ -n "${REPLY:-}" ] && [ "$REPLY" != "y" ]; then
        DEV_DIR="$REPLY"
      fi
      DEV_DIR="${DEV_DIR/#\~/$HOME}"
      mkdir -p "$DEV_DIR"

      TARGET="$DEV_DIR/vatn"
      if [ -d "$TARGET/.git" ]; then
        info "vatn already cloned — pulling latest…"
        git -C "$TARGET" pull --ff-only 2>/dev/null && ok "vatn up to date" || warn "Could not update"
      else
        info "Cloning vatn…"
        git clone "https://github.com/$VATN_ORG/$VATN_REPO.git" "$TARGET" \
          && ok "vatn → $TARGET" \
          || warn "Clone failed"
      fi

      printf "\n"
      printf "  ${BLD}Build everything:${RST}\n"
      printf "    ${CYN}cd %s && mvn clean install -DskipTests${RST}\n" "$TARGET"
      printf "\n"
      printf "  ${BLD}Deploy to local installation:${RST}\n"
      printf "    ${CYN}cp vatn-cli/target/vatn-cli-*.jar %s/lib/vatn-cli.jar${RST}\n" "$INSTALL_DIR"
      printf "    ${CYN}cp plugins/vatn-plugin-*/target/vatn-plugin-*.jar %s/plugins/${RST}\n" "$INSTALL_DIR"
      printf "    ${CYN}cp vatn-webadmin/target/vatn-webadmin.jar %s/lib/${RST}\n" "$INSTALL_DIR"
    fi
  fi
fi

# ── PATH setup ────────────────────────────────────────────────────────────────
if $INSTALL_CORE; then
  step "PATH"

  EXPORT_LINE="export PATH=\"$INSTALL_DIR/bin:\$PATH\"  # VATN"
  _add_to_rc() {
    local rc="$1"
    [ -f "$rc" ] || return
    grep -qF "$INSTALL_DIR/bin" "$rc" 2>/dev/null && { info "PATH already in $rc"; return; }
    { printf '\n# VATN runtime\n'; printf '%s\n' "$EXPORT_LINE"; } >>"$rc"
    ok "PATH added to $rc"
  }

  SHELL_NAME="$(basename "${SHELL:-bash}")"
  case "$SHELL_NAME" in
    zsh)  _add_to_rc "$HOME/.zshrc" ;;
    bash) _add_to_rc "$HOME/.bashrc"; _add_to_rc "$HOME/.bash_profile" ;;
    fish)
      mkdir -p "$HOME/.config/fish/conf.d"
      printf 'fish_add_path %s\n' "$INSTALL_DIR/bin" >"$HOME/.config/fish/conf.d/vatn.fish"
      ok "Fish path configured"
      ;;
    *) _add_to_rc "$HOME/.profile" ;;
  esac
  export PATH="$INSTALL_DIR/bin:$PATH"
fi

# ── Install manifest → log file ──────────────────────────────────────────────
# Write a clean, machine-readable installation manifest so the user can always
# find out exactly where each piece was installed.
{
  printf "\n"
  printf "=%.0s" {1..60}; printf "\n"
  printf "VATN INSTALLATION MANIFEST\n"
  printf "%-30s %s\n" "timestamp"    "$(date '+%Y-%m-%d %H:%M:%S %Z')"
  printf "%-30s %s\n" "installer"    "$0"
  printf "%-30s %s\n" "os_platform"  "$PLATFORM"
  printf "%-30s %s\n" "architecture" "$ARCH"
  printf "%-30s %s\n" "java_version" "$(java -version 2>&1 | head -1)"
  printf "=%.0s" {1..60}; printf "\n"
  printf "\n"

  printf "[DIRECTORIES]\n"
  printf "%-30s %s\n" "vatn_home"         "$INSTALL_DIR"
  printf "%-30s %s\n" "bin_dir"           "$INSTALL_DIR/bin"
  printf "%-30s %s\n" "lib_dir"           "$INSTALL_DIR/lib"
  printf "%-30s %s\n" "plugins_dir"       "$INSTALL_DIR/plugins"
  printf "%-30s %s\n" "config_dir"        "$INSTALL_DIR/config"
  printf "%-30s %s\n" "logs_dir"          "$INSTALL_DIR/logs"
  printf "\n"

  printf "[COMPONENTS]\n"
  printf "%-30s %s\n" "core_runtime"      "$($INSTALL_CORE && echo INSTALLED || echo SKIPPED)"
  printf "%-30s %s\n" "web_admin"         "$($INSTALL_WEBADMIN && echo INSTALLED || echo SKIPPED)"
  printf "%-30s %s\n" "plugins"           "$($INSTALL_PLUGINS && echo INSTALLED || echo SKIPPED)"
  printf "%-30s %s\n" "examples"          "$($INSTALL_EXAMPLES && echo INSTALLED || echo SKIPPED)"
  printf "\n"

  if $INSTALL_CORE; then
    printf "[CORE RUNTIME]\n"
    printf "%-30s %s\n" "launcher"          "$INSTALL_DIR/bin/vatn"
    printf "%-30s %s\n" "runtime_jar"       "$INSTALL_DIR/lib/vatn-cli.jar"
    printf "%-30s %s\n" "config_file"       "$INSTALL_DIR/config/vatn.conf"
    printf "%-30s %s\n" "release_tag"       "${LATEST_TAG:-unknown}"
    printf "\n"
  fi

  if $INSTALL_WEBADMIN; then
    printf "[WEB ADMIN]\n"
    WEBADMIN_BIN="$INSTALL_DIR/bin/vatn-webadmin"
    printf "%-30s %s\n" "binary"            "$WEBADMIN_BIN"
    printf "%-30s %s\n" "jar"               "$INSTALL_DIR/lib/vatn-webadmin.jar"
    printf "%-30s %s\n" "log_out"           "$INSTALL_DIR/logs/webadmin.out.log"
    printf "%-30s %s\n" "log_err"           "$INSTALL_DIR/logs/webadmin.err.log"
    printf "%-30s %s\n" "url_admin"         "http://localhost:8080/vatn/admin"
    printf "%-30s %s\n" "url_containers"    "http://localhost:8080/vatn/containers"
    if [ "$PLATFORM" = "macos" ]; then
      printf "%-30s %s\n" "launchd_plist"   "$HOME/Library/LaunchAgents/dev.vatn.webadmin.plist"
      printf "%-30s %s\n" "service_label"   "dev.vatn.webadmin"
      printf "%-30s %s\n" "manage_stop"     "launchctl stop dev.vatn.webadmin"
      printf "%-30s %s\n" "manage_start"    "launchctl start dev.vatn.webadmin"
    elif [ "$PLATFORM" = "linux" ]; then
      printf "%-30s %s\n" "systemd_service" "$HOME/.config/systemd/user/vatn-webadmin.service"
      printf "%-30s %s\n" "manage_stop"     "systemctl --user stop vatn-webadmin"
      printf "%-30s %s\n" "manage_start"    "systemctl --user start vatn-webadmin"
    fi
    printf "\n"
  fi

  if $INSTALL_PLUGINS; then
    printf "[PLUGINS]\n"
    printf "%-30s %s\n" "plugins_dir"       "$INSTALL_DIR/plugins"
    printf "%-30s %s\n" "plugin_count"      "${#SELECTED_PLUGINS[@]}"
    for p in "${SELECTED_PLUGINS[@]}"; do
      printf "%-30s %s\n" "plugin" "vatn-plugin-${p}.jar  →  $INSTALL_DIR/plugins/vatn-plugin-${p}.jar"
    done
    if [ "${#FAILED[@]:-0}" -gt 0 ] 2>/dev/null; then
      for p in "${FAILED[@]}"; do
        printf "%-30s %s\n" "plugin_not_released" "vatn-plugin-${p}  (build from source)"
      done
    fi
    printf "\n"
  fi

  if $INSTALL_EXAMPLES && [ -n "${DEV_DIR:-}" ]; then
    printf "[EXAMPLES]\n"
    printf "%-30s %s\n" "examples_dir"      "${DEV_DIR}/vatn/examples"
    printf "%-30s %s\n" "source_repo"       "https://github.com/RainerXE/vatn"
    printf "\n"
  fi

  printf "[PATH]\n"
  if $INSTALL_CORE; then
    printf "%-30s %s\n" "added_to_path"   "$INSTALL_DIR/bin"
    printf "%-30s %s\n" "shell_rc"        "$([ -n "${SHELL:-}" ] && echo "$HOME/.$(basename $SHELL)rc" || echo 'see shell rc')"
  fi
  printf "\n"
  printf "%-30s %s\n" "this_log"          "$INSTALL_LOG"
  printf "=%.0s" {1..60}; printf "\n"
} >> "$INSTALL_LOG"

# ── Summary ───────────────────────────────────────────────────────────────────
printf "\n${BLD}${GRN}"
printf "  ╔══════════════════════════════════════════════════════╗\n"
printf "  ║   ✔  VATN — Runtime for Personal Services           ║\n"
printf "  ║        installed successfully!                       ║\n"
printf "  ╚══════════════════════════════════════════════════════╝\n"
printf "${RST}\n"
printf "  ${BLD}Home:${RST}           %s\n" "$INSTALL_DIR"

if $INSTALL_CORE; then
  printf "  ${BLD}CLI launcher:${RST}   %s\n" "$INSTALL_DIR/bin/vatn"
  printf "  ${BLD}Runtime JAR:${RST}    %s\n" "$INSTALL_DIR/lib/vatn-cli.jar"
  printf "  ${BLD}Config:${RST}         %s\n" "$INSTALL_DIR/config/vatn.conf"
fi
if $INSTALL_PLUGINS; then
  printf "  ${BLD}Plugins dir:${RST}    %s\n" "$INSTALL_DIR/plugins"
  printf "  ${BLD}Plugins:${RST}        %s installed\n" "${#SELECTED_PLUGINS[@]}"
fi

printf "\n"

if $INSTALL_WEBADMIN; then
  printf "  ${BLD}VATN Web Admin:${RST}\n"
  printf "    Binary           →  ${CYN}%s${RST}\n" "$INSTALL_DIR/bin/vatn-webadmin"
  printf "    Admin Dashboard  →  ${CYN}http://localhost:8080/vatn/admin${RST}\n"
  printf "    Containers GUI   →  ${CYN}http://localhost:8080/vatn/containers${RST}\n"
  printf "    Logs             →  ${CYN}%s${RST}\n" "$INSTALL_DIR/logs/webadmin.out.log"
  if [ "$PLATFORM" = "macos" ]; then
    printf "    LaunchAgent      →  ${CYN}%s${RST}\n" "$HOME/Library/LaunchAgents/dev.vatn.webadmin.plist"
  elif [ "$PLATFORM" = "linux" ]; then
    printf "    Systemd service  →  ${CYN}%s${RST}\n" "$HOME/.config/systemd/user/vatn-webadmin.service"
  fi
  printf "    ${DIM}Set VATN_ADMIN_PASS + VATN_JWT_SECRET env vars in production.${RST}\n"
  printf "\n"
fi

if [ "${#FAILED[@]:-0}" -gt 0 ] 2>/dev/null; then
  warn "Plugins not yet released (copy JARs to $INSTALL_DIR/plugins/ once built):"
  for p in "${FAILED[@]}"; do printf "    ${DIM}• vatn-plugin-%s${RST}\n" "$p"; done
  printf "\n"
fi

printf "  ${BLD}Install log:${RST}    ${CYN}%s${RST}\n" "$INSTALL_LOG"
printf "\n"

printf "  ${BLD}Next steps:${RST}\n"
if $INSTALL_CORE; then
  printf "  ${DIM}1.${RST}  Reload shell:      ${CYN}source ~/.%src${RST}\n" "${SHELL_NAME:-bash}"
  printf "  ${DIM}2.${RST}  Verify:            ${CYN}vatn --version${RST}\n"
  printf "  ${DIM}3.${RST}  Create a project:  ${CYN}vatn init my-project${RST}\n"
  printf "  ${DIM}4.${RST}  Start a node:      ${CYN}vatn run${RST}\n"
fi
printf "\n"
printf "  Docs: ${CYN}https://github.com/RainerXE/vatn/blob/main/README.md${RST}\n\n"
