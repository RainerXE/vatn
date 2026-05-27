# VATN OIPC M4.6 Wire Protocol Alignment

This document formalizes the mapping between the **OIPC (Open Inter-Process Communication)** M4.6 wire protocol and the **VATN** plugin contract.

## 1. Greeting Frame (Fixed 64-bytes)

Sent once per connection before any other traffic.

| Byte | Field | VATN Mapping |
| :--- | :--- | :--- |
| 0–3 | Magic | `0x4F 0x49 0x50 0x43` ("OIPC") |
| 4 | Ver Major | `1` |
| 5 | Ver Minor | `0` |
| 6–7 | Flags | `0x0001` (Require Auth) |
| 8–11 | Codec Pref | `0x01` (JSON-L), `0x02` (MsgPack) |
| 12 | Mode | `0x04` (Full Duplex) |
| 13 | Transport | `0x01` (UDS), `0x02` (TCP) |
| 14–63 | Padding | Reserved for future expansion |

## 2. Handshake Lifecycle

Once the greeting is exchanged, the Node and Plugin engage in the following binary-safe handshake:

1. **GREETING** (Node → Plugin): The node provides its `NodeID` and `TrustLevel`.
2. **CAPABILITY_ACK** (Plugin → Node): The plugin sends its `PluginID` and links its `vatn-plugin.json` manifest.
3. **INITIALIZE** (Node → Plugin): Node confirms registry and provides the `VNodeContext` proxy endpoints.
4. **READY** (Plugin → Node): Plugin signals it is open for stream/message processing.

## 3. Frame Header (Fixed 22-bytes)

All subsequent messages use the OIPC frame header.

| Byte | Field | Description |
| :--- | :--- | :--- |
| 0–3 | Session ID | u32 Session identifier assigned by Node |
| 4–7 | Channel ID | u32 Channel identifier (0 = Control) |
| 8–15| Seq | u64 Monotonically increasing sequence number |
| 16 | Msg Type | u8 Message opcode (see below) |
| 17 | Flags | u8 Opcode-specific flags |
| 18 | Reserved | u8 Always 0x00 |
| 19–21| Payload Len | u24 size of the payload following the header |

### 3.1 Message Types

| Value | Type | Description |
| :--- | :--- | :--- |
| `0x01` | PUSH | Fire-and-forget message |
| `0x02` | REQUEST | Block/Async request |
| `0x03` | RESPONSE | Matching response for request |
| `0x04` | STREAM_DATA | Chunk of binary stream data |
| `0x05` | ACK | Protocol-level acknowledgment |
| `0x0B` | SHUTDOWN | Graceful connection termination (**New**) |

## 4. Shutdown Lifecycle (0x0B)

Allows for orderly resource cleanup between Node and Plugin.

### 4.1 Shutdown Modes (Flags)

- **RELAXED (`0x00`)**: Client sends shutdown and closes socket immediately. No ACK expected.
- **STRICT (`0x01`)**: Client sends shutdown and waits for an `ACK (0x05)` with matching `seq` before closing.

### 4.2 Message Flow (Strict)

```mermaid
graph TD
    A[Client] -->|Shutdown (type=0x0B, flags=0x01, seq=100, payload='Bye')| B[Host]
    B -->|Ack (type=0x05, seq=100)| A
    B -->|Close Socket| C[Resources Cleaned]
    A -->|Close Socket| D[Done]
```

### 4.3 Payload
Optional UTF-8 "Reason" string. Max length 1024 bytes. Useful for debugging termination causes.

## 5. Multiplexing

All data and control flows share the same physical transport (UDS/TCP).
- **Control Plane**: Port/Topic `vatn.control.*`
- **Data Plane**: Port/Topic `vatn.data.*`

Streaming binary data SHOULD prefer `DIRECT` mode if trust levels permit, bypassing the JSON-L codec for raw binary throughput.
