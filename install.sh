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
#    VATN_JAVA           — "graal" | "graalce" | "skip"   (default: interactive)
#    VATN_COMPONENTS     — "all" | comma-list of: core,webadmin,plugins,examples
#                          default: all components enabled
#    VATN_PLUGINS        — comma-list / "all" / "recommended"  (default: recommended)
# ==============================================================================
set -euo pipefail

# ── GitHub coordinates ────────────────────────────────────────────────────────
VATN_ORG="RainerXE"
VATN_REPO="vatn"
MIN_JAVA_MAJOR=21

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

# ── GraalVM choice ────────────────────────────────────────────────────────────
INSTALL_JAVA=false
GRAALVM_VARIANT=""

if [ -n "${VATN_JAVA:-}" ]; then
  case "$VATN_JAVA" in
    graal)   INSTALL_JAVA=true; GRAALVM_VARIANT="graal"   ;;
    graalce) INSTALL_JAVA=true; GRAALVM_VARIANT="graalce" ;;
    skip)    info "Skipping Java installation (VATN_JAVA=skip)." ;;
    *) warn "Unknown VATN_JAVA value '$VATN_JAVA' — skipping Java install." ;;
  esac
else
  if ! $JAVA_OK; then
    printf "\n"
    info "VATN recommends GraalVM for AOT compilation and peak performance."
    printf "\n"
    printf "  ${BLD}Install Java:${RST}\n"
    printf "   1) ${GRN}Oracle GraalVM${RST}   — GraalVM + JVMCI, free for development\n"
    printf "   2) ${CYN}GraalVM CE${RST}       — community edition, Apache 2.0\n"
    printf "   3) ${DIM}Skip${RST}             — configure Java manually\n\n"
    ask "Choose [1/2/3, default 1]:"
    case "${REPLY:-1}" in
      1) INSTALL_JAVA=true; GRAALVM_VARIANT="graal"   ;;
      2) INSTALL_JAVA=true; GRAALVM_VARIANT="graalce" ;;
      *) warn "Skipping Java. Ensure Java $MIN_JAVA_MAJOR+ is on your PATH." ;;
    esac
  else
    printf "\n"
    printf "  ${BLD}Upgrade to GraalVM?${RST} (enables native AOT compilation)\n"
    printf "   1) ${GRN}Oracle GraalVM${RST}   (recommended)\n"
    printf "   2) ${CYN}GraalVM CE${RST}       (Apache 2.0)\n"
    printf "   3) ${DIM}Keep current Java${RST}\n\n"
    ask "Choose [1/2/3, default 3]:"
    case "${REPLY:-3}" in
      1) INSTALL_JAVA=true; GRAALVM_VARIANT="graal"   ;;
      2) INSTALL_JAVA=true; GRAALVM_VARIANT="graalce" ;;
      *) info "Keeping existing Java." ;;
    esac
  fi
fi

# ── SDKMAN + GraalVM ──────────────────────────────────────────────────────────
if $INSTALL_JAVA; then
  step "Installing GraalVM via SDKMAN"

  SDKMAN_INIT="$HOME/.sdkman/bin/sdkman-init.sh"
  if [ ! -f "$SDKMAN_INIT" ]; then
    info "Installing SDKMAN..."
    curl -fsSL "https://get.sdkman.io" | bash
    ok "SDKMAN installed"
  else
    info "SDKMAN already present"
  fi

  # shellcheck source=/dev/null
  source "$SDKMAN_INIT"

  info "Finding latest GraalVM version ($GRAALVM_VARIANT)..."
  GRAAL_VER=$(sdk list java 2>/dev/null \
    | grep -E "^\s+\|.*[[:space:]]${GRAALVM_VARIANT}[[:space:]]" \
    | grep -v "local only" \
    | awk '{print $NF}' \
    | grep -E "^[0-9]" \
    | sort -t. -k1,1n -k2,2n -k3,3n -r \
    | head -1)

  if [ -z "$GRAAL_VER" ]; then
    warn "Could not auto-detect version. Available options:"
    sdk list java 2>/dev/null | grep "$GRAALVM_VARIANT" | head -10 || true
    printf "\n"
    ask "Enter version identifier (e.g. 25.0.1-graal):"
    GRAAL_VER="$REPLY"
    [ -z "$GRAAL_VER" ] && die "No version entered — aborting Java install."
  else
    info "Latest: $GRAAL_VER"
  fi

  sdk install java "$GRAAL_VER" </dev/null || die "GraalVM installation failed."
  sdk default java "$GRAAL_VER"
  export JAVA_HOME="${SDKMAN_DIR}/candidates/java/current"
  export PATH="$JAVA_HOME/bin:$PATH"
  ok "GraalVM $GRAAL_VER installed and set as default"
fi

command -v java &>/dev/null \
  || die "java not found after setup. Install Java $MIN_JAVA_MAJOR+ and re-run."
ok "Using: $(java -version 2>&1 | head -1)"

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
    warn "Could not download vatn-cli.jar — no release available yet."
    warn "Build from source: mvn clean install -DskipTests in the vatn/ directory"
    echo "# placeholder" >"$INSTALL_DIR/lib/vatn-cli.jar.placeholder"
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
    warn "vatn-webadmin binary not yet released — it will be available in the next release."
    warn "Build locally:  mvn clean package -pl vatn-webadmin --also-make -DskipTests"
    warn "Then copy:      cp vatn-webadmin/target/vatn-webadmin.jar $INSTALL_DIR/lib/"
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
      warn "Not yet released: $jar"
      rm -f "$INSTALL_DIR/plugins/$jar"
      FAILED+=("$plugin")
    fi
  done
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

    ask "Clone examples to [$DEFAULT_DEV]:"
    DEV_DIR="${REPLY:-$DEFAULT_DEV}"
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

      ask "Development directory [$DEFAULT_DEV]:"
      DEV_DIR="${REPLY:-$DEFAULT_DEV}"
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

# ── Summary ───────────────────────────────────────────────────────────────────
printf "\n${BLD}${GRN}"
printf "  ╔══════════════════════════════════════════════════════╗\n"
printf "  ║   ✔  VATN — Runtime for Personal Services           ║\n"
printf "  ║        installed successfully!                       ║\n"
printf "  ╚══════════════════════════════════════════════════════╝\n"
printf "${RST}\n"
printf "  ${BLD}Home:${RST}      %s\n" "$INSTALL_DIR"

if $INSTALL_CORE; then
  printf "  ${BLD}Config:${RST}    %s\n" "$INSTALL_DIR/config/vatn.conf"
fi
if $INSTALL_PLUGINS; then
  printf "  ${BLD}Plugins:${RST}   %s installed\n" "${#SELECTED_PLUGINS[@]}"
fi

printf "\n"

if $INSTALL_WEBADMIN; then
  printf "  ${BLD}VATN Web Admin:${RST}\n"
  printf "    Admin Dashboard  →  ${CYN}http://localhost:8080/vatn/admin${RST}\n"
  printf "    Containers GUI   →  ${CYN}http://localhost:8080/vatn/containers${RST}\n"
  printf "    ${DIM}Running as a background daemon (launchd/systemd).${RST}\n"
  printf "    ${DIM}Set VATN_ADMIN_PASS + VATN_JWT_SECRET env vars in production.${RST}\n"
  printf "\n"
fi

if [ "${#FAILED[@]:-0}" -gt 0 ] 2>/dev/null; then
  warn "Plugins not yet released (copy JARs to $INSTALL_DIR/plugins/ once built):"
  for p in "${FAILED[@]}"; do printf "    ${DIM}• vatn-plugin-%s${RST}\n" "$p"; done
  printf "\n"
fi

printf "  ${BLD}Next steps:${RST}\n"
if $INSTALL_CORE; then
  printf "  ${DIM}1.${RST}  Reload shell:      ${CYN}source ~/.%src${RST}\n" "${SHELL_NAME:-bash}"
  printf "  ${DIM}2.${RST}  Verify:            ${CYN}vatn --version${RST}\n"
  printf "  ${DIM}3.${RST}  Create a project:  ${CYN}vatn init my-project${RST}\n"
  printf "  ${DIM}4.${RST}  Start a node:      ${CYN}vatn run${RST}\n"
fi
printf "\n"
printf "  Docs: ${CYN}https://github.com/RainerXE/vatn/blob/main/README.md${RST}\n\n"
