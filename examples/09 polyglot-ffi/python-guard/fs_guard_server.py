#!/usr/bin/env python3
"""
fs_guard_server.py — VATN Python File-System Guard (subprocess/socket bridge)

Python cannot export a C ABI shared library the normal way, so we use a
different pattern: Java spawns this script as a subprocess and communicates
over a Unix domain socket (or named pipe on Windows) using a tiny length-
prefixed JSON protocol.

Why not just use the C version for Python tooling?
  - You already have Python logic (ML classifiers, existing security libs,
    custom policy engines) that would be hard to port.
  - This pattern lets you reuse ANY Python library (e.g. pathspec for
    .gitignore-style rules, or a scikit-learn anomaly detector).

Protocol (both directions):
  [4 bytes big-endian uint32 = body length] [UTF-8 JSON body]

Request JSON:
  { "path": "/abs/path", "operation": "READ|WRITE|DELETE|LIST|EXECUTE",
    "agent_id": "agent-123", "workspace": "/workspace/root" }

Response JSON:
  { "verdict": "ALLOW|BLOCK|REQUIRE_APPROVAL", "reason": "..." }
"""

import json
import os
import re
import socket
import struct
import sys
from pathlib import Path, PurePosixPath

# ---------------------------------------------------------------------------
# Verdict constants
# ---------------------------------------------------------------------------
ALLOW             = "ALLOW"
BLOCK             = "BLOCK"
REQUIRE_APPROVAL  = "REQUIRE_APPROVAL"

# ---------------------------------------------------------------------------
# Guard logic — put your Python-native policy here
# ---------------------------------------------------------------------------

SENSITIVE_SUFFIXES = {
    ".env", ".pem", ".key", ".p12", ".pfx",
    ".htpasswd", ".kdbx",
}
SENSITIVE_NAMES = {
    "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
    "secrets.yaml", "secrets.yml",
}
SENSITIVE_DIRS = {
    ".ssh", ".gnupg", "etc",
}
SENSITIVE_ETC_FILES = {
    "shadow", "passwd", "sudoers",
}

def _is_sensitive(path: str) -> bool:
    p = Path(path)
    # Suffix match
    if p.suffix.lower() in SENSITIVE_SUFFIXES:
        return True
    # Exact name match
    if p.name in SENSITIVE_NAMES:
        return True
    # Parent directory rules
    parts = set(p.parts)
    if ".ssh" in parts or ".gnupg" in parts:
        return True
    if "etc" in parts and p.name in SENSITIVE_ETC_FILES:
        return True
    return False

def _is_within_workspace(path: str, workspace: str) -> bool:
    if not workspace:
        return True
    try:
        Path(path).relative_to(workspace)
        return True
    except ValueError:
        return False

def evaluate(req: dict) -> dict:
    path      = req.get("path", "")
    operation = req.get("operation", "").upper()
    workspace = req.get("workspace", "")

    # 1. Workspace containment
    if workspace and not _is_within_workspace(path, workspace):
        return {"verdict": BLOCK, "reason": "path escapes workspace boundary"}

    # 2. Sensitive-file rules
    if _is_sensitive(path):
        if operation == "READ":
            return {"verdict": REQUIRE_APPROVAL,
                    "reason": "sensitive file read requires approval"}
        if operation in ("WRITE", "DELETE"):
            return {"verdict": BLOCK,
                    "reason": "write/delete of sensitive file is blocked"}

    # 3. EXECUTE — always approval
    if operation == "EXECUTE":
        return {"verdict": REQUIRE_APPROVAL,
                "reason": "file execution requires human approval"}

    # 4. DELETE — approval even for non-sensitive
    if operation == "DELETE":
        return {"verdict": REQUIRE_APPROVAL,
                "reason": "file deletion requires human approval"}

    # 5. Default allow
    return {"verdict": ALLOW, "reason": "allowed"}

# ---------------------------------------------------------------------------
# Socket server
# ---------------------------------------------------------------------------

def _recv_msg(conn: socket.socket) -> bytes | None:
    """Read one length-prefixed message from the socket."""
    header = b""
    while len(header) < 4:
        chunk = conn.recv(4 - len(header))
        if not chunk:
            return None
        header += chunk
    length = struct.unpack(">I", header)[0]
    body = b""
    while len(body) < length:
        chunk = conn.recv(length - len(body))
        if not chunk:
            return None
        body += chunk
    return body

def _send_msg(conn: socket.socket, data: bytes) -> None:
    """Write one length-prefixed message to the socket."""
    conn.sendall(struct.pack(">I", len(data)) + data)

def serve(socket_path: str) -> None:
    # Remove stale socket file
    try:
        os.unlink(socket_path)
    except FileNotFoundError:
        pass

    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(socket_path)
    server.listen(5)

    # Signal readiness to the Java parent process
    sys.stdout.write("READY\n")
    sys.stdout.flush()

    while True:
        conn, _ = server.accept()
        with conn:
            while True:
                raw = _recv_msg(conn)
                if raw is None:
                    break
                try:
                    req  = json.loads(raw.decode("utf-8"))
                    resp = evaluate(req)
                except Exception as e:
                    resp = {"verdict": BLOCK, "reason": f"guard error: {e}"}
                _send_msg(conn, json.dumps(resp).encode("utf-8"))

if __name__ == "__main__":
    # Socket path is passed as first argument by the Java subprocess launcher
    sock = sys.argv[1] if len(sys.argv) > 1 else "/tmp/vatn-fs-guard.sock"
    serve(sock)
