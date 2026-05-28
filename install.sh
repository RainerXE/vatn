#!/usr/bin/env bash
# ==============================================================================
#  VATN Installer — Linux / macOS
#
#  One-liner (interactive):
#    curl -fsSL https://raw.githubusercontent.com/RainerXE/vatn/main/install.sh -o install.sh && bash install.sh
#
#  Non-interactive (piped) — uses defaults, installs recommended plugins:
#    curl -fsSL https://raw.githubusercontent.com/RainerXE/vatn/main/install.sh | bash
#
#  Env-var overrides (non-interactive mode):
#    VATN_INSTALL_DIR   — target directory   (default: ~/.vatn)
#    VATN_JAVA          — "graal" | "graalce" | "skip"  (default: prompted)
#    VATN_PLUGINS       — comma-list of plugin names, "all", or "recommended"
# ==============================================================================
set -euo pipefail

# ── GitHub coordinates ────────────────────────────────────────────────────────
VATN_ORG="RainerXE"
VATN_CORE_REPO="vatn"
VATN_PLUGINS_REPO="vatn-plugins"
MIN_JAVA_MAJOR=21

# ── terminal output ───────────────────────────────────────────────────────────
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  RED='\033[0;31m' GRN='\033[0;32m' YLW='\033[1;33m'
  BLU='\033[0;34m' CYN='\033[0;36m' BLD='\033[1m' DIM='\033[2m' RST='\033[0m'
else
  RED='' GRN='' YLW='' BLU='' CYN='' BLD='' DIM='' RST=''
fi

info()    { printf "  ${CYN}▶${RST}  %s\n"          "$*"; }
ok()      { printf "  ${GRN}✔${RST}  %s\n"          "$*"; }
warn()    { printf "  ${YLW}⚠${RST}  %s\n"          "$*"; }
err()     { printf "  ${RED}✖${RST}  %s\n" "$*" >&2; }
step()    { printf "\n${BLD}${BLU}━━  %s  ━━${RST}\n" "$*"; }
die()     { err "$*"; exit 1; }

# read from /dev/tty so the installer works when piped via curl | bash
ask() {
  printf "  ${CYN}?${RST}  %s " "$*"
  if [ -t 0 ]; then
    read -r REPLY
  elif [ -e /dev/tty ]; then
    read -r REPLY < /dev/tty
  else
    REPLY=""
    printf "(non-interactive — using default)\n"
  fi
}

# ── banner ────────────────────────────────────────────────────────────────────
printf "\n${BLD}${BLU}"
printf "  ╔══════════════════════════════════════════════════════╗\n"
printf "  ║                                                      ║\n"
printf "  ║   ⬡  VATN — Federated OS for Personal AI            ║\n"
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

# required tools
for cmd in curl unzip; do
  command -v "$cmd" &>/dev/null || die "Required tool not found: $cmd. Install it and retry."
done

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

# honour env override
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
    printf "   3) ${DIM}Skip${RST}             — configure Java manually\n"
    printf "\n"
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
    printf "   3) ${DIM}Keep current Java${RST}\n"
    printf "\n"
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

  sdk install java "$GRAAL_VER" < /dev/null || die "GraalVM installation failed."
  sdk default java "$GRAAL_VER"
  export JAVA_HOME="${SDKMAN_DIR}/candidates/java/current"
  export PATH="$JAVA_HOME/bin:$PATH"
  ok "GraalVM $GRAAL_VER installed and set as default"
fi

# final sanity check
command -v java &>/dev/null \
  || die "java not found after setup. Install Java $MIN_JAVA_MAJOR+ and re-run."
ok "Using: $(java -version 2>&1 | head -1)"

# ── Installation directory ────────────────────────────────────────────────────
step "Installation directory"

DEFAULT_DIR="${VATN_INSTALL_DIR:-$HOME/.vatn}"
printf "\n"
ask "Install location [$DEFAULT_DIR]:"
INSTALL_DIR="${REPLY:-$DEFAULT_DIR}"
INSTALL_DIR="${INSTALL_DIR/#\~/$HOME}"   # expand ~

if [ -d "$INSTALL_DIR/lib" ] || [ -d "$INSTALL_DIR/plugins" ]; then
  warn "Existing installation found at $INSTALL_DIR."
  ask "Upgrade in place? [Y/n]:"
  [[ "${REPLY:-y}" =~ ^[Nn]$ ]] && die "Installation cancelled."
fi

mkdir -p "$INSTALL_DIR"/{bin,lib,plugins,config,logs}
ok "Directory layout ready: $INSTALL_DIR"

# ── Download VATN core JAR ────────────────────────────────────────────────────
step "Downloading VATN runtime"

LATEST_TAG=$(curl -fsSL \
  "https://api.github.com/repos/${VATN_ORG}/${VATN_CORE_REPO}/releases/latest" \
  2>/dev/null | grep '"tag_name"' | cut -d'"' -f4 || true)
LATEST_TAG="${LATEST_TAG:-latest}"
info "Release tag: $LATEST_TAG"

CORE_URL="https://github.com/${VATN_ORG}/${VATN_CORE_REPO}/releases/download/${LATEST_TAG}/vatn-cli.jar"
if curl -fsSL --progress-bar -o "$INSTALL_DIR/lib/vatn-cli.jar" "$CORE_URL" 2>/dev/null; then
  ok "vatn-cli.jar downloaded"
else
  warn "Could not download vatn-cli.jar — no release available yet."
  warn "Build from source and copy to: $INSTALL_DIR/lib/vatn-cli.jar"
  # create placeholder so launcher can give a useful error message
  echo "# placeholder" > "$INSTALL_DIR/lib/vatn-cli.jar.placeholder"
fi

# ── Plugin selection ──────────────────────────────────────────────────────────
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
  "openai     |OpenAI / LLM client                                         |no"
  "metrics    |Prometheus /metrics endpoint (Micrometer)                   |no"
  "email      |SMTP email via Jakarta Mail                                  |no"
  "slack      |Slack webhook + Events API                                   |no"
  "s3         |AWS S3 / compatible object storage                           |no"
  "comm       |Communication hub: Telegram, Signal, RCS                    |no"
  "indexer    |Full-text search indexing                                    |no"
  "scraper    |Headless web scraping                                        |no"
  "activitypub|ActivityPub / Fediverse federation                           |no"
)

if [ -z "${VATN_PLUGINS:-}" ]; then
  printf "\n"
  printf "  ${BLD}Select plugins to install:${RST}\n"
  printf "  ${DIM}admin is always included.  [*] = recommended default.${RST}\n\n"
  printf "  ${GRN}[always]${RST}  %-14s  Admin dashboard UI + JVM metrics + plugin management\n" "vatn-plugin-admin"
  printf "\n"

  IDX=1
  for entry in "${PLUGINS[@]}"; do
    IFS='|' read -r PNAME PDESC PDEF <<< "$entry"
    PNAME="${PNAME// /}"
    PDEF="${PDEF// /}"
    MARKER="     "
    [ "$PDEF" = "yes" ] && MARKER="${YLW}[*]${RST}  "
    printf "  %s${BLD}%2d)${RST}  %-14s  %s\n" "$MARKER" "$IDX" "vatn-plugin-$PNAME" "$PDESC"
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
          SELECTED_PLUGINS+=("${PNAME// /}")
          break
        fi
      done
      IDX=$((IDX + 1))
    done
    ;;
esac

# ── Download plugins ──────────────────────────────────────────────────────────
step "Downloading plugins"

PLUGIN_TAG=$(curl -fsSL \
  "https://api.github.com/repos/${VATN_ORG}/${VATN_PLUGINS_REPO}/releases/latest" \
  2>/dev/null | grep '"tag_name"' | cut -d'"' -f4 || true)
PLUGIN_TAG="${PLUGIN_TAG:-latest}"

FAILED=()
for plugin in "${SELECTED_PLUGINS[@]}"; do
  jar="vatn-plugin-${plugin}.jar"
  url="https://github.com/${VATN_ORG}/${VATN_PLUGINS_REPO}/releases/download/${PLUGIN_TAG}/${jar}"
  info "Fetching $jar…"
  if curl -fsSL --progress-bar -o "$INSTALL_DIR/plugins/$jar" "$url" 2>/dev/null; then
    ok "$jar"
  else
    warn "Not available yet: $jar"
    rm -f "$INSTALL_DIR/plugins/$jar"
    FAILED+=("$plugin")
  fi
done

# ── Default configuration ─────────────────────────────────────────────────────
step "Configuration"

CONF="$INSTALL_DIR/config/vatn.conf"
if [ ! -f "$CONF" ]; then
cat > "$CONF" << 'CONF_EOF'
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

# ── Launcher script ───────────────────────────────────────────────────────────
step "Launcher"

LAUNCHER="$INSTALL_DIR/bin/vatn"
cat > "$LAUNCHER" << LAUNCHER_EOF
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

# append plugin JARs to classpath
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

# ── PATH setup ────────────────────────────────────────────────────────────────
step "PATH"

EXPORT_LINE="export PATH=\"$INSTALL_DIR/bin:\$PATH\"  # VATN"
_add_to_rc() {
  local rc="$1"
  [ -f "$rc" ] || return
  grep -qF "$INSTALL_DIR/bin" "$rc" 2>/dev/null && { info "PATH already in $rc"; return; }
  { printf '\n# VATN runtime\n'; printf '%s\n' "$EXPORT_LINE"; } >> "$rc"
  ok "PATH added to $rc"
}

SHELL_NAME="$(basename "${SHELL:-bash}")"
case "$SHELL_NAME" in
  zsh)  _add_to_rc "$HOME/.zshrc" ;;
  bash) _add_to_rc "$HOME/.bashrc"; _add_to_rc "$HOME/.bash_profile" ;;
  fish)
    mkdir -p "$HOME/.config/fish/conf.d"
    printf 'fish_add_path %s\n' "$INSTALL_DIR/bin" > "$HOME/.config/fish/conf.d/vatn.fish"
    ok "Fish path configured"
    ;;
  *)    _add_to_rc "$HOME/.profile" ;;
esac

# also export for the current session
export PATH="$INSTALL_DIR/bin:$PATH"

# ── Developer setup (optional) ───────────────────────────────────────────────
step "Developer setup (optional)"

printf "\n"
printf "  Clone the VATN source repos to build custom plugins,\n"
printf "  contribute to the runtime, or explore the codebase.\n"
printf "  Requires: git, Maven 3.9+\n\n"

ask "Clone source repos for local development? [y/N]:"
if [[ "${REPLY:-n}" =~ ^[Yy]$ ]]; then

  if ! command -v git &>/dev/null; then
    warn "git not found on PATH — skipping clone."
    printf "  Install git, then run:\n"
    printf "  ${CYN}  git clone https://github.com/%s/%s.git${RST}\n" "$VATN_ORG" "$VATN_CORE_REPO"
    printf "  ${CYN}  git clone https://github.com/%s/%s.git${RST}\n" "$VATN_ORG" "$VATN_PLUGINS_REPO"
  else
    # detect sensible default dev directory
    DEFAULT_DEV=""
    for candidate in "$HOME/Development" "$HOME/Projects" "$HOME/dev" "$HOME/code" "$HOME/src"; do
      [ -d "$candidate" ] && { DEFAULT_DEV="$candidate"; break; }
    done
    [ -z "$DEFAULT_DEV" ] && DEFAULT_DEV="$HOME/Development"

    ask "Development directory [$DEFAULT_DEV]:"
    DEV_DIR="${REPLY:-$DEFAULT_DEV}"
    DEV_DIR="${DEV_DIR/#\~/$HOME}"
    mkdir -p "$DEV_DIR"

    for repo in "$VATN_CORE_REPO" "$VATN_PLUGINS_REPO"; do
      TARGET="$DEV_DIR/$repo"
      if [ -d "$TARGET/.git" ]; then
        info "$repo already cloned — pulling latest..."
        git -C "$TARGET" pull --ff-only 2>/dev/null && ok "$repo up to date" || warn "Could not update $repo"
      else
        info "Cloning $repo..."
        git clone "https://github.com/$VATN_ORG/$repo.git" "$TARGET" \
          && ok "$repo → $TARGET" \
          || warn "Clone failed: $repo (check network/permissions)"
      fi
    done

    printf "\n"
    ask "Also clone vatn-demo (example ports and migration tutorials)? [y/N]:"
    if [[ "${REPLY:-n}" =~ ^[Yy]$ ]]; then
      DEMO="$DEV_DIR/vatn-demo"
      if [ -d "$DEMO/.git" ]; then
        git -C "$DEMO" pull --ff-only 2>/dev/null || true
        info "vatn-demo already present"
      else
        git clone "https://github.com/$VATN_ORG/vatn-demo.git" "$DEMO" \
          && ok "vatn-demo → $DEMO" \
          || warn "Clone failed: vatn-demo"
      fi
    fi

    printf "\n"
    ok "Source repos ready in $DEV_DIR"
    printf "\n"
    printf "  ${BLD}Build the runtime:${RST}\n"
    printf "    ${CYN}cd %s/%s${RST}\n" "$DEV_DIR" "$VATN_CORE_REPO"
    printf "    ${CYN}mvn clean install -DskipTests${RST}   # builds api + core + cli\n"
    printf "\n"
    printf "  ${BLD}Build plugins:${RST}\n"
    printf "    ${CYN}cd %s/%s${RST}\n" "$DEV_DIR" "$VATN_PLUGINS_REPO"
    printf "    ${CYN}mvn clean install -DskipTests${RST}\n"
    printf "\n"
    printf "  ${BLD}Deploy your build to the local VATN installation:${RST}\n"
    printf "    ${CYN}cp %s/%s/vatn-cli/target/vatn-cli-*.jar %s/lib/vatn-cli.jar${RST}\n" \
      "$DEV_DIR" "$VATN_CORE_REPO" "$INSTALL_DIR"
    printf "    ${CYN}cp %s/%s/vatn-plugin-*/target/vatn-plugin-*.jar %s/plugins/${RST}\n" \
      "$DEV_DIR" "$VATN_PLUGINS_REPO" "$INSTALL_DIR"
  fi
fi

# ── Summary ───────────────────────────────────────────────────────────────────
printf "\n${BLD}${GRN}"
printf "  ╔══════════════════════════════════════════════════════╗\n"
printf "  ║   ✔  VATN installed successfully!                   ║\n"
printf "  ╚══════════════════════════════════════════════════════╝\n"
printf "${RST}\n"
printf "  ${BLD}Home:${RST}      %s\n"    "$INSTALL_DIR"
printf "  ${BLD}Config:${RST}    %s\n"    "$CONF"
printf "  ${BLD}Plugins:${RST}   %s installed\n" "${#SELECTED_PLUGINS[@]}"
printf "\n"

if [ "${#FAILED[@]}" -gt 0 ]; then
  warn "Plugins not yet released (copy JARs to $INSTALL_DIR/plugins/ once built):"
  for p in "${FAILED[@]}"; do printf "    ${DIM}• vatn-plugin-%s${RST}\n" "$p"; done
  printf "\n"
fi

printf "  ${BLD}Next steps:${RST}\n"
printf "  ${DIM}1.${RST}  Reload shell:      ${CYN}source ~/.%src${RST}\n"  "$SHELL_NAME"
printf "  ${DIM}2.${RST}  Verify install:    ${CYN}vatn --version${RST}\n"
printf "  ${DIM}3.${RST}  Create a project:  ${CYN}vatn init my-project${RST}\n"
printf "  ${DIM}4.${RST}  Start a node:      ${CYN}vatn run${RST}\n"
printf "\n"
printf "  Docs: ${CYN}https://github.com/RainerXE/vatn/blob/main/README.md${RST}\n\n"
