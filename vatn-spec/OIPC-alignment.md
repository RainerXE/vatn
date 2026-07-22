# VATN OIPC Wire Protocol Alignment — OIPC v2.12 "Relentless" + v2.13

This document maps the **OIPC v2.12 "Relentless"** wire protocol and the **v2.13** additive
delta to the VATN plugin contract. It is a language-neutral reference for implementors writing
plugins in Python, Rust, C, or any other language.

- **v2.12** defines the base V3 binary header, HELLO handshake, opcodes, transports, shutdown
  lifecycle, trust levels, and multiplexing.
- **v2.13** is an additive, backward-compatible delta: an optional 64-byte Greeting carrying
  `auth_token` + `client_id`, an in-band HTTP CONNECT tunnel, and per-connection identity
  surfacing via `ScopedValue`. The V3 header and on-wire `ver_minor` stay `12`.

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

## 2. Greeting Layout (64 bytes, little-endian for multi-byte fields) — v2.13

The Greeting is sent *before* the V3 frame stream. Both the Greeting and a V3 frame begin with
the magic `"OIPC"`, so they are distinguished by the byte at offset 4:

- offset 4 `== 2` → Greeting (v2.13) — parse it, then continue into the V3 loop.
- offset 4 `== 3` → V3 frame directly (legacy v2.12 client) — skip the Greeting.

| offset | size | field | notes |
|--------|------|-------|-------|
| 0 | 4 | magic | `"OIPC"` |
| 4 | 1 | ver_major | `2` |
| 5 | 1 | ver_minor | `12` (unchanged on the wire) |
| 6 | 2 | flags | Greeting_Flags u16 (little-endian) |
| 8 | 4 | codec_pref | `[4]Codec` (use `0` = unspecified) |
| 12 | 1 | mode_flags | `0` (unused by VATN) |
| 13 | 1 | channel_mode | `0` |
| 14 | 1 | transport | `0x01` UDS / `0x02` TCP |
| 15 | 4 | session_hint | u32 (0) |
| 19 | 16 | client_id | stable per-client identity |
| 35 | 24 | auth_token | all-zero = AUTH_NONE |
| 59 | 5 | _reserved | zero |

### Greeting Flags (u16, little-endian)

| bit | mask | name | meaning |
|-----|------|------|---------|
| 0 | `1 << 0` | `TLS_Required` | client requests/requires TLS |
| 1 | `1 << 1` | `Auth_Required` | client expects token auth |
| 2 | `1 << 2` | `Compression_Supported` | client supports compression |
| 3 | `1 << 3` | `Tunneled_HTTP` | Greeting arrived over an HTTP CONNECT tunnel (informational/diagnostic only) |

The `Tunneled_HTTP` flag is **diagnostic only**; it does not change the handshake or framing
behavior.

---

## 3. Connection Bootstrapping

### 3.1 HELLO Handshake (binary mode)

Every binary connection **must** begin with a HELLO control frame before any data frame.
If a v2.13 Greeting was exchanged, the HELLO follows immediately after it.

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

### 3.2 Legacy JSON handshake (OipcProcessPluginProxy)

`OipcProcessPluginProxy` manages `OUT_OF_PROCESS_BIN` plugins using plain JSON lines
for maximum language compatibility:

| Direction | Message |
|---|---|
| VATN → Plugin | `{"type":"VATN_INIT","pluginId":"<id>"}` |
| Plugin → VATN | Any JSON line (triggers heartbeat reset) |
| VATN → Plugin | `{"type":"STATUS_CHECK"}` |
| VATN → Plugin | `{"type":"VATN_SHUTDOWN"}` |

---

## 4. HTTP CONNECT Tunnel — v2.13

On a **new TCP connection**, the server peeks the first byte without consuming it:

- `'C'` (`0x43`) → HTTP `CONNECT` tunnel to terminate *before* the Greeting.
- `'O'` (`0x4F`) → direct OIPC (normal Greeting/V3 path).

The `CONNECT` exchange is server-side terminated, then the stream is transparently positioned
for the subsequent Greeting/V3 exchange on the **same socket**. The tunnel is out-of-band with
respect to the OIPC frame stream.

- Gated by `vatn.ipc.http_connect_enabled` (default `false`).
- Target validated against `vatn.ipc.connect_allowlist` (comma-separated `host:port`; **empty =
  allow any**).
- Replies `HTTP/1.1 200 Connection established` (allow) or `HTTP/1.1 403 Forbidden` (deny). On
  deny, the connection is closed.

| System property | Default | Description |
|---|---|---|
| `vatn.ipc.http_connect_enabled` | `false` | Gate for server-side CONNECT termination |
| `vatn.ipc.connect_allowlist` | `""` | Comma-separated `host:port`; empty = allow any |

> A malicious client may not stream an unbounded header: the CONNECT request read is bounded by
> `MAX_CONNECT_HEADER_BYTES` (8192) in `OipcMessagingTransport`; an over-limit request is treated
> as malformed and the connection is closed.

---

## 5. VATN API Mapping

### 5.1 VOipcMessageType ↔ V2.12 Opcodes

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

### 5.2 VTransport ↔ V2.12 Transports

| `VTransport` | V2.12 transport | Notes |
|---|---|---|
| `UDS` | Unix Domain Sockets | Default; `java.io.tmpdir/vatn/vatn-<uuid>.sock` |
| `TCP` | TCP Loopback | Fallback; `vatn.ipc.force_tcp=true` |
| `SECURE_TUNNEL` | TCP Remote + TLS | Planned for Release 2 (Lattice) |
| `LOCAL` | In-process | No socket; same-JVM bypass |
| `MQTT` | Edge gateway | IoT bridge — not part of core OIPC |

---

## 6. Auth Token Semantics — v2.13

The 24-byte `auth_token` field in the Greeting is a **pre-shared secret**:

- **All-zero = `AUTH_NONE`** (no token presented).
- When `vatn.ipc.require_auth_token=true` **AND** the transport is **not** a Unix Domain Socket,
  the server validates the presented token (constant-time compare) against the configured
  `vatn.ipc.auth_token`. On mismatch the connection is closed *before* the V3/HELLO stream begins.
- **UDS bypasses auth** — filesystem trust is assumed; the token is not checked on UDS.
- The expected token is derived from the `vatn.ipc.auth_token` system property: its UTF-8 bytes
  are **zero-padded or truncated to 24 bytes** deterministically. Configure both sides with the
  same string.

| System property | Default | Description |
|---|---|---|
| `vatn.ipc.require_auth_token` | `false` | When true, non-UDS connections must present a matching token |
| `vatn.ipc.auth_token` | `""` | Expected token; UTF-8 bytes, zero-padded/truncated to 24 |

---

## 7. Per-Connection Identity Surfacing — v2.13

The identity captured from the Greeting (`client_id`, `auth_token`) is surfaced to message
handlers via `ScopedValue`s set around subscriber dispatch:

- `VatnSecurity.CURRENT_AUTH_TOKEN`
- `VatnSecurity.CURRENT_CLIENT_ID`

These are set on a per-connection basis (mirroring the existing `CURRENT_PLUGIN_ID` pattern) and
are **read-only after the handshake**. Handlers read them via `.get()`; they are never passed as
parameters.

---

## 8. Lifecycle Channels

Plugins and nodes communicate via these primary control channels over OIPC:

| Channel | Protocol | Purpose |
|---|---|---|
| `vatn.lifecycle.init` | JSON-L | Node → Plugin: request setup |
| `vatn.lifecycle.ready` | Signal | Plugin → Node: ready signal |
| `vatn.lifecycle.shutdown` | JSON-L | Orderly termination |
| `vatn.discovery.peer` | Broadcast | Peer node discovery and heartbeat |

---

## 9. Shutdown Lifecycle (`SHUTDOWN` / `0x0B`)

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

## 10. Trust Levels and Flow Permissions

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

## 11. Multiplexing

Both control and data traffic share the same physical socket (full-duplex):

- **Control plane**: topics matching `vatn.control.*` — policy, lifecycle, health
- **Data plane**: topics matching `vatn.data.*` — pub/sub message payloads

Binary streams (`STREAM_DATA` frames) **should** use `DIRECT` mode when `VTrustLevel`
permits, bypassing the JSON-L codec for raw throughput.

---

## 12. Backward Compatibility Statement

- A **v2.12 client** (V3 directly, no Greeting) works unchanged. The server inspects byte[4] to
  distinguish `2` (Greeting) from `3` (V3) and handles both.
- The on-wire **`ver_minor` stays `12`** — v2.13 is signalled structurally (the optional Greeting
  frame and the `Tunneled_HTTP` flag), not by a minor-version bump.
- `OipcProcessPluginProxy` remains **v2.12 JSON** by design (legacy mode) and is untouched.

---

## 13. Reference Implementation (Java)

| Concern | Class |
|---|---|
| Server accept / Greeting parse / auth / CONNECT tunnel | `OipcMessagingTransport` (`vatn-core`) |
| Client-side Greeting emission helper | `OipcGreeting` (`vatn-core`) |
| Tests (v2.12 + v2.13) | `OipcMessagingTest` (`vatn-core/src/test`) |

*OIPC v2.12 "Relentless" + v2.13 — Updated 2026-07-22.*
*Full wire-format spec: [`docs/oipc-protocol.md`](../docs/oipc-protocol.md)*
