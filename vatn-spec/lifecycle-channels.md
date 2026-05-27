# VATN Lifecycle & Policy Resolution

This document defines the language-neutral channel conventions and security policy resolution rules for the VATN ecosystem.

## 1. Lifecycle Channels

Plugins and Nodes MUST communicate via the following primary control channels:

| Channel | Protocol | Purpose |
| :--- | :--- | :--- |
| `vatn.lifecycle.init` | JSON-L | Sent by Node to Plugin to request setup. |
| `vatn.lifecycle.ready` | Signal | Sent by Plugin to Node once ready. |
| `vatn.lifecycle.shutdown`| JSON-L | Orderly termination signal. |
| `vatn.discovery.peer` | Broadcast| Peer node discovery and heartbeat. |

## 2. Policy Resolution: "Most Restrictive Wins"

VATN utilizes a non-permissive security model. When multiple **Policy Interjectors** are present (e.g., a node runner policy and a user-defined security filter), the following resolution algorithm MUST be followed:

1. **Iteration**: The Node Controller queries all registered `VPolicyInterjector` implementations.
2. **Decision Collection**: Decisions are collected as `ALLOW`, `DENY`, or `ABSTAIN`.
3. **Resolution**:
    - If ANY interjector returns **`DENY`**, the request is **REJECTED** immediately.
    - If ALL interjectors return **`ABSTAIN`**, the request is **REJECTED** (default deny).
    - If at least one interjector returns **`ALLOW`** and NO interjector returns **`DENY`**, the request is **APPROVED**.
4. **Feedback Signal**: When a request is rejected by a specific interjector, the Node Controller MUST emit a `dev.vatn.api.security.PolicyViolation` event to the `vatn.monitor.security` channel, including the `InterjectorID` and the `RequestedPolicy`.

## 3. Trust Levels vs Flow Modes

| Trust Level | Permitted Modes | Permitted Directions |
| :--- | :--- | :--- |
| `NONE` | None | None |
| `SANDBOXED` | `MEDIATED` | `INBOUND` Only |
| `RESTRICTED` | `MEDIATED` | `INBOUND`, `OUTBOUND` |
| `FULL` | `MEDIATED`, `DIRECT` | `BIDIRECTIONAL` |
| `VERIFIED_FEDERATED` | `MEDIATED`, `DIRECT` | `BIDIRECTIONAL` |
