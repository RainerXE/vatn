# OIPC Protocol Specification — v2.12

**Octet IPC (OIPC)** is the standard communication protocol between VATN and out-of-process plugins. It is language-agnostic (Python, C, Odin, Rust, …), supports payloads from a few bytes up to 256 MB, and transparently degrades from a V3 binary framing down to a plain JSON envelope so that simple clients can participate without a binary parser.

---

## 1. Transport

OIPC operates over two transport types, chosen at startup with automatic fallback:

| Priority | Transport | When used |
|---|---|---|
| 1 | **Unix Domain Sockets (UDS)** | Default; `java.io.tmpdir/vatn/vatn-<uuid>.sock` |
| 2 | **TCP Loopback** | Fallback when UDS is unavailable, or forced via `vatn.ipc.force_tcp=true` |

The socket path (UDS) or port (TCP) is communicated to spawned plugin processes via the environment variable **`VATN_IPC_PORT`** (TCP port number). For UDS the path is embedded in the node's plugin descriptor.

Trust filtering applies to TCP connections only (UDS is filesystem-backed and inherently local):

```
# System property — comma-separated trusted IPs (TCP only)
vatn.ipc.trusted_hosts=127.0.0.1,0:0:0:0:0:0:0:1   # default
```

---

## 2. Wire Format

Every OIPC connection must begin with the byte `0x4F` (`O`) — the first byte of the `OIPC` magic. Connections that open with any other byte are dropped immediately.

### 2.1 V3 "Relentless" Binary Header (18 bytes)

Mandatory for payloads > 1 MB and for all control/feedback frames.

```
Offset  Size  Field
──────  ────  ─────────────────────────────────────
     0     4  magic          "OIPC"
     4     1  wire_version   3
     5     1  mode_flags     see §3
     6     4  payload_length u32 big-endian, current frame only
    10     4  message_id     u32 random entropy — correlation ID
    14     4  sequence_idx   u32 zero-based chunk index
    18     N  payload
```

### 2.2 Mode Flags (byte 5)

| Flag | Mask | Meaning |
|---|---|---|
| `MODE_BINARY` | `0x02` | Always set for binary frames |
| `IS_CHUNKED` | `0x08` | Frame is part of a multi-frame sequence |
| `IS_LAST` | `0x10` | Final frame in a chunked sequence |
| `IS_CONTROL` | `0x20` | Control or feedback frame |

Combination `IS_CONTROL \| IS_CHUNKED` is illegal and causes immediate connection drop.

---

## 3. Connection Bootstrapping

### 3.1 HELLO Handshake

Every binary connection **must** begin with a HELLO control frame before any data frame. Clients that send data before a successful HELLO are immediately disconnected.

**Client → Server (HELLO request)**

```
Header:  wire_version=3, flags=0x22 (IS_CONTROL | MODE_BINARY)
Payload: [Type=0x05][Major:u8][Minor:u8][NodeID:UTF-8 string]
```

**Server → Client (HELLO ACK)**

```
Header:  wire_version=3, flags=0x22
Payload: [Type=0x01][Major:u8][Minor:u8][NodeID:UTF-8 string]
```

Version compatibility rule: **Major must match exactly** (currently `2`). Minor version is informational.

### 3.2 Reassembly TTL

To prevent resource exhaustion from abandoned chunked transfers, incomplete reassembly buffers are purged after an inactivity timeout:

```
vatn.ipc.reassembly_ttl_ms=30000   # default: 30 s
```

All reassemblers for a client are also purged immediately on connection close.

---

## 4. Chunking & Reassembly

Senders **must** fragment payloads larger than the chunk threshold (default 1 MB) into multiple V3 frames sharing the same `message_id`. The receiver reassembles them in order.

Rules:
1. `sequence_idx` must be strictly monotonic (0, 1, 2, …). A gap or duplicate triggers a `NACK` feedback frame and the connection is considered broken.
2. The reassembler dispatches the complete payload only upon receiving a frame with `IS_LAST` set.
3. Accumulated payload must not exceed `MAX_MESSAGE_SIZE` (default 256 MB); frames exceeding it are dropped.

```
# System properties
vatn.ipc.chunk_size=1048576        # 1 MB per frame (default)
vatn.ipc.max_message_size=268435456 # 256 MB total (default)
```

---

## 5. Control & Feedback Channel

Both sides can send control frames at any time on the same socket (full-duplex). Control frames always use the V3 header with `IS_CONTROL` set.

**Control frame payload (12 bytes)**

```
Offset  Size  Field
──────  ────  ─────────────────────────────────────
     0     1  type        0x01=ACK  0x02=NACK  0x03=PAUSE  0x04=RESUME
     1     4  control_id  message_id or sequence_idx being referenced
     5     7  reserved    zero-filled
```

| Type | Direction | Meaning |
|---|---|---|
| `0x01` ACK | Server → Client | `message_id` fully reassembled and dispatched |
| `0x02` NACK | Server → Client | Retransmit `sequence_idx` for `message_id` |
| `0x03` PAUSE | Either | Stop sending — receiver buffer approaching limit |
| `0x04` RESUME | Either | Ready to receive again |

---

## 6. Plugin Lifecycle

Out-of-process plugins are spawned and supervised by `VWatchdogServiceImpl`. The lifecycle from the plugin's perspective:

1. **SPAWN** — VATN forks the plugin binary. `VATN_IPC_PORT` is set in the environment with the TCP port the proxy expects.
2. **CONNECT** — Plugin opens a TCP connection to `127.0.0.1:$VATN_IPC_PORT`.
3. **HANDSHAKE** — Plugin sends a HELLO frame (binary) or a `VATN_INIT` JSON message (legacy JSON mode). VATN acknowledges.
4. **RUNNING** — Normal pub/sub message exchange.
5. **HEALTH POLL** — Watchdog sends `STATUS_CHECK` (JSON) or a ping every 5 s. Plugins must respond within 10 s or are marked as hung.
6. **SHUTDOWN** — VATN sends `VATN_SHUTDOWN` (JSON) or closes the socket; plugin should exit cleanly.

### Legacy JSON control messages (OipcProcessPluginProxy)

`OipcProcessPluginProxy` manages OUT_OF_PROCESS_BIN plugins using V1 JSON:

| Direction | Message |
|---|---|
| VATN → Plugin | `{"type":"VATN_INIT","pluginId":"<id>"}` |
| Plugin → VATN | Any JSON line (triggers heartbeat reset) |
| VATN → Plugin | `{"type":"STATUS_CHECK"}` |
| VATN → Plugin | `{"type":"VATN_SHUTDOWN"}` |

---

## 7. Watchdog & Supervision

`VWatchdogServiceImpl` monitors all out-of-process plugins every **5 s**:

| Check | Threshold | Action |
|---|---|---|
| Process alive | — | Log crash warning |
| Heartbeat | 10 s silence | Log hang; kill if `vatn.watchdog.fail_on_limits=true` |
| RSS memory (soft) | 512 MB | Log warning |
| RSS memory (hard) | 2048 MB | Kill if `fail_on_limits=true` |

RSS is measured via `ps -o rss= -p <pid>` (macOS/Linux).

---

## 8. Message Types (VOipcMessageType)

High-level semantic opcodes used by application-layer pub/sub routing (not part of the binary wire header):

| Opcode | Name | Description |
|---|---|---|
| `0x01` | PUSH | Fire-and-forget publish |
| `0x02` | REQUEST | Request expecting a response |
| `0x03` | RESPONSE | Reply to a REQUEST |
| `0x04` | STREAM_DATA | Chunk of a binary stream |
| `0x05` | ACK | Protocol-level acknowledgment |
| `0x06` | ERROR | Protocol-level error |
| `0x07` | SUBSCRIBE | Subscribe to a topic |
| `0x08` | UNSUBSCRIBE | Unsubscribe from a topic |
| `0x09` | PING | Keep-alive ping |
| `0x0A` | PONG | Keep-alive pong |
| `0x0B` | SHUTDOWN | Graceful shutdown signal |

---

## 9. Configuration Reference

| System property | Default | Description |
|---|---|---|
| `vatn.ipc.force_tcp` | `false` | Skip UDS, bind TCP directly |
| `vatn.ipc.trusted_hosts` | `127.0.0.1,::1` | Comma-separated trusted IPs (TCP only) |
| `vatn.ipc.chunk_size` | `1048576` | Max bytes per binary frame (1 MB) |
| `vatn.ipc.max_message_size` | `268435456` | Max reassembled payload (256 MB) |
| `vatn.ipc.reassembly_ttl_ms` | `30000` | Incomplete reassembly timeout (ms) |

---

## 10. Security

| Transport | Auth mode | Notes |
|---|---|---|
| UDS | None (filesystem ACL) | Only local processes can connect |
| TCP Loopback | IP allowlist | `vatn.ipc.trusted_hosts` |
| TCP Remote | Token or TLS | Planned for Lattice (Release 2) |

---

*OIPC v2.12 — "Relentless". Implemented in `OipcMessagingTransport`, `OipcProcessPluginProxy`, `VWatchdogServiceImpl`.*
