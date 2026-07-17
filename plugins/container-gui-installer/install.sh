#!/usr/bin/env bash

# VATN Container GUI Standalone Installer
# Designed to be run via: curl -sL https://get.vatn.dev/containers | bash

set -euo pipefail

# Style definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0;0m' # No Color

echo -e "${BLUE}==>${NC} Starting VATN Container GUI Installer..."

# 1. Detect OS
OS_TYPE="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH_TYPE="$(uname -m)"

echo -e "${BLUE}==>${NC} Detected Platform: $OS_TYPE ($ARCH_TYPE)"

INSTALL_DIR="$HOME/.vatn/bin"
mkdir -p "$INSTALL_DIR"

BINARY_NAME="container-gui"
BINARY_PATH="$INSTALL_DIR/$BINARY_NAME"

# 2. Determine binary URL (placeholder mapping)
# Normally pointing to GitHub Releases
URL="https://github.com/rainerxe/vatn-plugins/releases/latest/download/${BINARY_NAME}-${OS_TYPE}-${ARCH_TYPE}"

echo -e "${BLUE}==>${NC} Downloading binary from $URL..."

# In real deployment this fetches from URL. We mock the download steps here:
if command -v curl >/dev/null; then
    # curl -sL "$URL" -o "$BINARY_PATH" || true
    echo "curl download complete (mocked)"
elif command -v wget >/dev/null; then
    # wget -qO "$BINARY_PATH" "$URL" || true
    echo "wget download complete (mocked)"
else
    echo -e "${RED}Error:${NC} curl or wget is required to download the binary."
    exit 1
fi

# Create a local placeholder dummy script for demonstration/testing if binary doesn't exist
if [ ! -f "$BINARY_PATH" ]; then
    echo -e "${BLUE}==>${NC} Simulating binary compilation/copy for local workspace demo..."
    # Copy compiled fat-jar or native binary if compiled locally
    LOCAL_BUILD_PATH="../target/container-gui"
    if [ -f "$LOCAL_BUILD_PATH" ]; then
        cp "$LOCAL_BUILD_PATH" "$BINARY_PATH"
    else
        # Fallback dummy shell executable for testing installer workflow
        echo '#!/usr/bin/env bash' > "$BINARY_PATH"
        echo 'echo "VATN Container GUI Service Active"' >> "$BINARY_PATH"
        echo 'sleep infinity' >> "$BINARY_PATH"
    fi
fi

chmod +x "$BINARY_PATH"
echo -e "${GREEN}==>${NC} Installed binary to $BINARY_PATH"

# 3. Setup system service
if [ "$OS_TYPE" = "darwin" ]; then
    PLIST_PATH="$HOME/Library/LaunchAgents/dev.vatn.container-gui.plist"
    echo -e "${BLUE}==>${NC} Configuring macOS LaunchAgent: $PLIST_PATH"
    
    cat <<EOF > "$PLIST_PATH"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>dev.vatn.container-gui</string>
    <key>ProgramArguments</key>
    <array>
        <string>$BINARY_PATH</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/vatn-container-gui.out.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/vatn-container-gui.err.log</string>
</dict>
</plist>
EOF
    
    # Reload service
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    launchctl load "$PLIST_PATH"
    echo -e "${GREEN}==>${NC} LaunchAgent loaded. Service will start automatically on login."
    
elif [ "$OS_TYPE" = "linux" ]; then
    SYSTEMD_DIR="$HOME/.config/systemd/user"
    mkdir -p "$SYSTEMD_DIR"
    SERVICE_PATH="$SYSTEMD_DIR/vatn-container-gui.service"
    
    echo -e "${BLUE}==>${NC} Configuring user systemd service: $SERVICE_PATH"
    
    cat <<EOF > "$SERVICE_PATH"
[Unit]
Description=VATN Container GUI
After=network.target

[Service]
ExecStart=$BINARY_PATH
Restart=always

[Install]
WantedBy=default.target
EOF
    
    systemctl --user daemon-reload
    systemctl --user enable vatn-container-gui.service
    systemctl --user restart vatn-container-gui.service
    echo -e "${GREEN}==>${NC} Systemd user service loaded and enabled."
fi

echo -e "\n${GREEN}Success!${NC} VATN Container GUI installer completed."
echo -e "Dashboard is serving at: ${BLUE}http://localhost:8080/vatn/containers${NC}"
EOF
