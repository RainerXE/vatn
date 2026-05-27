# VATN OIPC v2.12 Wire Protocol Alignment

This document maps the **OIPC v2.12 "Relentless"** wire protocol to the VATN plugin
contract. It is a language-neutral reference for implementors writing plugins in Python,
Rust, C, or any other language.

For the full wire-format specification see [`docs/oipc-protocol.md`](../docs/oipc-protocol.md).

---

## 1. Wire Format Summary

Every OIPC connection opens with the magic byte `0x4F` (`O`). Connections that start with
any other byte are dropped immediately.

### V3 "Relentless" Binary Header (18 bytes)

Used for all binary frames (payloads > 1 MB are mandatory; control frames always use this form).

```
Offset  Size  Field
──────  ────  ─────────────────────────────────────
     0     4  magic          "OIPC"  (0x4F 0x49 0x50 0x43)
     4     1  wire_version   3
     5     1  mode_flags     see §2
     6     4  payload_length u32 big-endian — current frame only
    10     4  message_id     u32 random entropy — correlation ID
    14     4  sequence_idx   u32 zero-based chunk index
    18     N  payload
```

### Mode Flags (byte 5)

| Flag | Mask | Meaning |
|---|---|---|
| `MODE_BINARY` | `0x02` | Always set for binary frames |
| `IS_CHUNKED` | `0x08` | Part of a multi-frame sequence |
| `IS_LAST` | `0x10` | Final frame in a chunked sequence |
| `IS_CONTROL` | `0x20` | Control or feedback frame |

`IS_CONTROL | IS_CHUNKED` is illegal and causes an immediate connection drop.

---

## 2. Connection Bootstrapping

### 2.1 HELLO Handshake (binary mode)

Every binary connection **must** begin with a HELLO control frame before any data frame.

**Client → Server (HELLO request)**
```
Header:  wire_version=3, flags=0x22 (IS_CONTROL | MODE_BINARY)
Payload: [Type=0x05][Major:u8=2][Minor:u8=12][NodeID:UTF-8 string]
```

**Server → Client (HELLO ACK)**
```
Header:  wire_version=3, flags=0x22
Payload: [Type=0x01][Major:u8=2][Minor:u8=12][NodeID:UTF-8 string]
```

Version compatibility rule: **Major must match exactly (currently `2`)**.

### 2.2 Legacy JSON handshake (OipcProcessPluginProxy)

`OipcProcessPluginProxy` manages `OUT_OF_PROCESS_BIN` plugins using plain JSON lines
for maximum language compatibility:

| Direction | Message |
|---|---|
| VATN → Plugin | `{"type":"VATN_INIT","pluginId":"<id>"}` |
| Plugin → VATN | Any JSON line (triggers heartbeat reset) |
| VATN → Plugin | `{"type":"STATUS_CHECK"}` |
| VATN → Plugin | `{"type":"VATN_SHUTDOWN"}` |

---

## 3. VATN API Mapping

### 3.1 VOipcMessageType ↔ V2.12 Opcodes

| `VOipcMessageType` | Opcode | Role |
|---|---|---|
| `PUSH` | `0x01` | Fire-and-forget publish |
| `REQUEST` | `0x02` | Request expecting a response |
| `RESPONSE` | `0x03` | Reply to a REQUEST |
| `STREAM_DATA` | `0x04` | Chunk of a binary stream |
| `ACK` | `0x05` | Protocol-level acknowledgment |
| `ERROR` | `0x06` | Protocol-level error |
| `SUBSCRIBE` | `0x07` | Subscribe to a topic |
| `UNSUBSCRIBE` | `0x08` | Unsubscribe from a topic |
| `PING` | `0x09` | Keep-alive ping |
| `PONG` | `0x0A` | Keep-alive pong |
| `SHUTDOWN` | `0x0B` | Graceful shutdown signal |

### 3.2 VTransport ↔ V2.12 Transports

| `VTransport` | V2.12 transport | Notes |
|---|---|---|
| `UDS` | Unix Domain Sockets | Default; `java.io.tmpdir/vatn/vatn-<uuid>.sock` |
| `TCP` | TCP Loopback | Fallback; `vatn.ipc.force_tcp=true` |
| `SECURE_TUNNEL` | TCP Remote + TLS | Planned for Release 2 (Lattice) |
| `LOCAL` | In-process | No socket; same-JVM bypass |
| `MQTT` | Edge gateway | IoT bridge — not part of core OIPC |

---

## 4. Lifecycle Channels

Plugins and nodes communicate via these primary control channels over OIPC:

| Channel | Protocol | Purpose |
|---|---|---|
| `vatn.lifecycle.init` | JSON-L | Node → Plugin: request setup |
| `vatn.lifecycle.ready` | Signal | Plugin → Node: ready signal |
| `vatn.lifecycle.shutdown` | JSON-L | Orderly termination |
| `vatn.discovery.peer` | Broadcast | Peer node discovery and heartbeat |

---

## 5. Shutdown Lifecycle (`SHUTDOWN` / `0x0B`)

Two modes controlled by the flags byte:

| Mode | Flag | Behaviour |
|---|---|---|
| `RELAXED` | `0x00` | Sender closes socket immediately; no ACK expected |
| `STRICT` | `0x01` | Sender waits for `ACK (0x05)` with matching `message_id` before closing |

**Strict flow:**
```
Client → SHUTDOWN (type=0x0B, flags=0x01, message_id=N, payload=UTF-8 reason)
Server → ACK     (type=0x05, control_id=N)
Server closes socket
Client closes socket
```

Optional payload: UTF-8 reason string, max 1 024 bytes.

---

## 6. Trust Levels and Flow Permissions

VATN enforces a "Most Restrictive Wins" security model across all `VPolicyInterjector`
registrations. Trust levels map to permitted transport modes and directions:

| `VTrustLevel` | Permitted modes | Permitted directions |
|---|---|---|
| `NONE` | None | None |
| `SANDBOXED` | `MEDIATED` | `INBOUND` only |
| `RESTRICTED` | `MEDIATED` | `INBOUND`, `OUTBOUND` |
| `FULL` | `MEDIATED`, `DIRECT` | `BIDIRECTIONAL` |
| `VERIFIED_FEDERATED` | `MEDIATED`, `DIRECT` | `BIDIRECTIONAL` |

`VERIFIED_FEDERATED` is reserved for cryptographically bonded nodes (Release 2).
All Release 1 plugins run at `FULL` or below.

### Policy resolution algorithm

1. Node queries all registered `VPolicyInterjector` implementations.
2. If **any** returns `DENY` → request rejected immediately.
3. If **all** return `ABSTAIN` → request rejected (default-deny).
4. If at least one returns `ALLOW` and none returns `DENY` → request approved.

---

## 7. Multiplexing

Both control and data traffic share the same physical socket (full-duplex):

- **Control plane**: topics matching `vatn.control.*` — policy, lifecycle, health
- **Data plane**: topics matching `vatn.data.*` — pub/sub message payloads

Binary streams (`STREAM_DATA` frames) **should** use `DIRECT` mode when `VTrustLevel`
permits, bypassing the JSON-L codec for raw throughput.

---

*OIPC v2.12 — Updated 2026-05-26.*
*Full wire-format spec: [`docs/oipc-protocol.md`](../docs/oipc-protocol.md)*
