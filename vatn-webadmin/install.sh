#!/usr/bin/env bash

# VATN Web Admin Installer
# Usage (remote): curl -sL https://get.vatn.dev/webadmin | bash
# Usage (local):  ./install.sh

set -euo pipefail

# Style definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0;0m'

BINARY_NAME="vatn-webadmin"
INSTALL_DIR="$HOME/.vatn/bin"
BINARY_PATH="$INSTALL_DIR/$BINARY_NAME"
VERSION="${VATN_VERSION:-latest}"

echo -e "${BLUE}==> VATN Web Admin Installer${NC}"
echo -e "${BLUE}==> Version: ${VERSION}${NC}"

# 1. Detect OS & architecture
OS_TYPE="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH_TYPE="$(uname -m)"
case "$ARCH_TYPE" in
  x86_64)  ARCH_TYPE="amd64" ;;
  aarch64) ARCH_TYPE="arm64" ;;
  arm64)   ARCH_TYPE="arm64" ;;
esac

echo -e "${BLUE}==> Platform: $OS_TYPE / $ARCH_TYPE${NC}"

mkdir -p "$INSTALL_DIR"

# 2. Download or use locally built binary
URL="https://github.com/rainerxe/vatn/releases/${VERSION}/download/${BINARY_NAME}-${OS_TYPE}-${ARCH_TYPE}"

echo -e "${BLUE}==> Looking for binary...${NC}"

# Check for a locally built native image first (dev workflow)
LOCAL_NATIVE="$(dirname "$0")/target/${BINARY_NAME}"
LOCAL_JAR="$(dirname "$0")/target/${BINARY_NAME}-1.0-alpha.14.jar"

if [ -f "$LOCAL_NATIVE" ]; then
    echo -e "${BLUE}==> Found local native binary: $LOCAL_NATIVE${NC}"
    cp "$LOCAL_NATIVE" "$BINARY_PATH"
elif [ -f "$LOCAL_JAR" ]; then
    echo -e "${BLUE}==> Found local fat-jar, wrapping as executable script...${NC}"
    JAR_DEST="$INSTALL_DIR/${BINARY_NAME}.jar"
    cp "$LOCAL_JAR" "$JAR_DEST"
    cat > "$BINARY_PATH" <<WRAPPER
#!/usr/bin/env bash
exec java -jar "$JAR_DEST" "\$@"
WRAPPER
elif command -v curl >/dev/null; then
    echo -e "${BLUE}==> Downloading from ${URL}${NC}"
    curl -sL "$URL" -o "$BINARY_PATH"
elif command -v wget >/dev/null; then
    echo -e "${BLUE}==> Downloading from ${URL}${NC}"
    wget -qO "$BINARY_PATH" "$URL"
else
    echo -e "${RED}Error: curl or wget required. Please install one and retry.${NC}"
    exit 1
fi

chmod +x "$BINARY_PATH"
echo -e "${GREEN}==> Installed: $BINARY_PATH${NC}"

# 3. Register as background service
if [ "$OS_TYPE" = "darwin" ]; then
    PLIST_LABEL="dev.vatn.webadmin"
    PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"
    echo -e "${BLUE}==> Configuring macOS LaunchAgent: $PLIST_PATH${NC}"

    cat > "$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${PLIST_LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${BINARY_PATH}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$HOME/.vatn/logs/webadmin.out.log</string>
    <key>StandardErrorPath</key>
    <string>$HOME/.vatn/logs/webadmin.err.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>VATN_ADMIN_USER</key>
        <string>admin</string>
    </dict>
</dict>
</plist>
EOF
    mkdir -p "$HOME/.vatn/logs"
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    launchctl load "$PLIST_PATH"
    echo -e "${GREEN}==> LaunchAgent loaded. Starts automatically on login.${NC}"

elif [ "$OS_TYPE" = "linux" ]; then
    SYSTEMD_DIR="$HOME/.config/systemd/user"
    mkdir -p "$SYSTEMD_DIR"
    SERVICE_PATH="$SYSTEMD_DIR/vatn-webadmin.service"
    echo -e "${BLUE}==> Configuring systemd user service: $SERVICE_PATH${NC}"

    cat > "$SERVICE_PATH" <<EOF
[Unit]
Description=VATN Web Admin
Documentation=https://vatn.dev/webadmin
After=network.target

[Service]
ExecStart=${BINARY_PATH}
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
SyslogIdentifier=vatn-webadmin

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable vatn-webadmin.service
    systemctl --user restart vatn-webadmin.service
    echo -e "${GREEN}==> Systemd user service enabled and started.${NC}"
else
    echo -e "${YELLOW}==> Unknown OS '$OS_TYPE'. Binary installed to $BINARY_PATH but no service was registered.${NC}"
    echo -e "${YELLOW}    Run manually: $BINARY_PATH${NC}"
fi

echo ""
echo -e "${GREEN}✓ VATN Web Admin installed successfully!${NC}"
echo -e "  Admin Dashboard : ${BLUE}http://localhost:8080/vatn/admin${NC}"
echo -e "  Containers GUI  : ${BLUE}http://localhost:8080/vatn/containers${NC}"
echo -e ""
echo -e "${YELLOW}Tip: Set VATN_ADMIN_PASS and VATN_JWT_SECRET env vars before starting in production.${NC}"
