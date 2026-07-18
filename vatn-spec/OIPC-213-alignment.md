# VATN OIPC v2.13 Wire Protocol Alignment

This document maps the **OIPC v2.13** delta to the VATN plugin contract. It is additive and
**fully backward compatible** with [`OIPC-212-alignment.md`](./OIPC-212-alignment.md): the V3
binary header and the HELLO handshake are byte-for-byte unchanged, and the on-wire
`ver_minor` stays `12`.

v2.13 adds three things:

1. an *optional* 64-byte **Greeting** bootstrap carrying `auth_token` + `client_id`,
2. a **Tunneled_HTTP** greeting flag (bit 3),
3. an in-band **HTTP CONNECT** tunnel that may precede the Greeting on a TCP connection.

For the full wire-format specification see [`docs/oipc-protocol.md`](../docs/oipc-protocol.md).

---

## 1. Greeting Layout (64 bytes, little-endian for multi-byte fields)

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

---

## 2. Greeting Flags (u16, little-endian)

| bit | mask | name | meaning |
|-----|------|------|---------|
| 0 | `1 << 0` | `TLS_Required` | client requests/requires TLS |
| 1 | `1 << 1` | `Auth_Required` | client expects token auth |
| 2 | `1 << 2` | `Compression_Supported` | client supports compression |
| 3 | `1 << 3` | `Tunneled_HTTP` | **NEW** — Greeting arrived over an HTTP CONNECT tunnel (informational/diagnostic only) |

The `Tunneled_HTTP` flag is **diagnostic only**; it does not change the handshake or framing
behavior.

---

## 3. auth_token Semantics

The 24-byte `auth_token` field is a **pre-shared secret**:

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

## 4. HTTP CONNECT Tunnel

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

## 5. Per-Connection Identity Surfacing (M5.2)

The identity captured from the Greeting (`client_id`, `auth_token`) is surfaced to message
handlers via `ScopedValue`s set around subscriber dispatch:

- `VatnSecurity.CURRENT_AUTH_TOKEN`
- `VatnSecurity.CURRENT_CLIENT_ID`

These are set on a per-connection basis (mirroring the existing `CURRENT_PLUGIN_ID` pattern) and
are **read-only after the handshake**. Handlers read them via `.get()`; they are never passed as
parameters.

---

## 6. Backward Compatibility Statement

- A **v2.12 client** (V3 directly, no Greeting) works unchanged. The server inspects byte[4] to
  distinguish `2` (Greeting) from `3` (V3) and handles both.
- The on-wire **`ver_minor` stays `12`** — v2.13 is signalled structurally (the optional Greeting
  frame and the `Tunneled_HTTP` flag), not by a minor-version bump.
- `OipcProcessPluginProxy` remains **v2.12 JSON** by design (legacy mode) and is untouched.

---

## 7. Reference Implementation (Java)

| Concern | Class |
|---|---|
| Server accept / Greeting parse / auth / CONNECT tunnel | `OipcMessagingTransport` (`vatn-core`) |
| Client-side Greeting emission helper | `OipcGreeting` (`vatn-core`) |
| Tests (v2.12 + v2.13) | `OipcMessagingTest` (`vatn-core/src/test`) |

*OIPC v2.13 — additive delta over v2.12 "Relentless". Updated 2026-07-19.*
*Full wire-format spec: [`docs/oipc-protocol.md`](../docs/oipc-protocol.md)*
