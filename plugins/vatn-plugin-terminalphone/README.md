# vatn-plugin-terminalphone
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Anonymous, end-to-end encrypted voice and text over Tor hidden services — a VATN-native port of the [TerminalPhone](https://gitlab.com/here_forawhile/terminalphone) walkie-talkie concept.

> **Inspired by:** [TerminalPhone](https://gitlab.com/here_forawhile/terminalphone) by *here_forawhile* — a self-contained Bash script for anonymous, encrypted voice communication over Tor. This plugin brings the same model to the JVM: record a complete voice message, encrypt it, sign it, and deliver it to a peer's `.onion` address with no accounts, no infrastructure, and no cleartext on the wire.

---

## What It Does

- Routes all traffic through the local Tor SOCKS5 proxy — peers are addressed by `.onion` hostname
- AES-256-CBC encryption (GCM and CTR also supported) with a pre-shared secret derived via PBKDF2
- Per-message HMAC-SHA256 signing to prevent replay attacks and detect tampering
- Store-and-forward voice model: record a complete clip, then send — no streaming required
- In-call encrypted text messaging
- Zero-knowledge relay mode: passes encrypted frames between peers without decrypting
- QR code generation of the local address for secure out-of-band exchange
- HTTP management API at `/terminalphone`

## Quick Start

```java
VNodeRunner.create(8080)
    .addPlugin(new TerminalPhonePlugin(
        TerminalPhoneConfig.builder("exchange-this-secret-out-of-band")
            .cipher("AES-256-CBC")
            .torHost("127.0.0.1")
            .torPort(9050)
            .listenPort(54321)
            .hmacSigning(true)
            .build()
    ))
    .addPlugin(new MyPlugin())
    .start();
```

Tor must be running locally with the SOCKS5 proxy on the configured port (default 9050).

### Using TerminalPhoneService From Another Plugin

```java
public class MyPlugin implements VNodePlugin {

    @Override
    public void onInitialize(VNodeContext ctx) {
        TerminalPhoneService phone = ctx.getService(TerminalPhoneService.class).orElseThrow();

        // Print QR code so the peer can scan our address
        System.out.println(phone.getQrCode());

        // Handle incoming calls and messages
        phone.onCallConnected(peer -> System.out.println("Connected to " + peer));
        phone.onTextMessage(msg    -> System.out.println(">> " + msg));
        phone.onVoiceMessage(pcm   -> {
            try { phone.playVoice(pcm); }
            catch (Exception e) { e.printStackTrace(); }
        });

        // Dial a peer
        try {
            phone.call("abc123def456.onion");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Push-to-Talk Pattern

```java
TerminalPhoneService phone = ctx.getService(TerminalPhoneService.class).orElseThrow();

// Record 3 seconds from the microphone, then send
byte[] clip = phone.recordVoice(3_000);
phone.sendVoice(clip);

// Or send a text message
phone.sendText("Standing by.");
```

### Group Call (Relay Mode)

```java
// On the relay node — forwards encrypted frames without decrypting them
TerminalPhoneConfig relayConfig = TerminalPhoneConfig.builder("shared-secret")
    .listenPort(54321)
    .relayMode(true)
    .build();

// Callers connect to the relay's address instead of dialing each other directly
// Relay capacity: ~3–5 callers on mobile, ~5–10 on a dedicated machine (Tor bandwidth bound)
```

---

## Configuration Reference

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `sharedSecret` | `String` | — | **Required.** Pre-shared key exchanged out-of-band. Used to derive both the AES key and HMAC key via PBKDF2. |
| `cipher` | `String` | `AES-256-CBC` | Encryption algorithm. Options: `AES-256-CBC`, `AES-256-GCM`, `AES-256-CTR`. |
| `torSocksHost` | `String` | `127.0.0.1` | Tor SOCKS5 proxy host. |
| `torSocksPort` | `int` | `9050` | Tor SOCKS5 proxy port. |
| `listenPort` | `int` | `54321` | Local TCP port to accept incoming calls on. |
| `hmacSigning` | `boolean` | `true` | Append and verify a per-message HMAC-SHA256 signature. Prevents replay attacks. |
| `relayMode` | `boolean` | `false` | Start a zero-knowledge relay on `listenPort + 1`. |

---

## HTTP Endpoints

All endpoints are mounted under `/terminalphone`.

### `GET /terminalphone/address`

Returns the local node address.

```json
{"address": "abc123.vatn:54321"}
```

---

### `GET /terminalphone/qr`

Returns the local address as an ASCII QR code (`text/plain`) for scanning with a camera or another terminal. Share this with your peer over a secure out-of-band channel.

---

### `GET /terminalphone/status`

```json
{"address": "abc123.vatn:54321", "sessions": 1}
```

---

### `POST /terminalphone/call`

Dial a peer by their address. Completes after the encrypted handshake.

**Request:**
```json
{"address": "peeronionaddress.onion"}
```

**Response:**
```json
{"status": "dialing"}
```

---

### `POST /terminalphone/hangup`

Hang up all active sessions.

```json
{"status": "hung_up"}
```

---

### `POST /terminalphone/text`

Send an encrypted text message to all connected peers.

**Request:**
```json
{"message": "Hello from the other side"}
```

**Response:**
```json
{"status": "sent"}
```

---

## Security Model

| Property | Detail |
|----------|--------|
| **Transport** | Tor hidden services — IP addresses are never exchanged |
| **Key derivation** | PBKDF2WithHmacSHA256, 100 000 iterations, 512-bit output split into AES key + HMAC key |
| **Encryption** | AES-256-CBC with a fresh random IV per message (or GCM/CTR as configured) |
| **Integrity** | HMAC-SHA256 over `type byte ‖ ciphertext` verifies each frame before decryption |
| **Replay protection** | HMAC covers the full encrypted frame; stateless but prevents bit-flipping |
| **Relay mode** | Relay nodes forward ciphertext only — they cannot read content |

**Limitations (inherited from the original TerminalPhone design):**
- No forward secrecy — a compromised shared secret exposes all past messages
- The shared secret must be exchanged through a secure out-of-band channel (Signal, in-person, etc.)
- Passive Tor traffic correlation attacks remain theoretically possible

---

## Dependencies

| Library | Purpose |
|---------|---------|
| `vatn-api` (provided) | VATN plugin interfaces |
| `jackson-databind` | HTTP endpoint JSON |
| `slf4j-api` | Logging |
| `com.google.zxing:core` | QR code generation |
| Java Sound API (`javax.sound.sampled`) | Microphone capture and speaker playback — no additional dependency |
| Java Cryptography Architecture (JCA) | AES, HMAC-SHA256, PBKDF2 — built into the JDK |
