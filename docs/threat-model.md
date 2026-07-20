# VATN Threat Model

**Date:** 2026-07-20 · **Scope:** the VATN node runtime, its wire protocols, plugin ecosystem, bundled webadmin, and agentic surfaces.
**Method:** surface-by-surface. For each: trust assumptions → attack categories → covering test suite → open gaps.
**Rule:** every confirmed finding becomes an adversarial test *before* its fix lands (see `docs/security/adversarial-review-ritual.md`).

---

## Trust boundaries (the short version)

1. **Network ↔ node** — HTTP(S) routes, OIPC (TCP/UDS), WebSocket upgrades. Anything on the network is hostile.
2. **Plugin JAR ↔ runtime** — plugins run in-process with a trust *level* (`FULL`/`RESTRICTED`/`SANDBOXED`/`NONE`) but no memory/CPU sandbox. A misbehaving plugin must never hang boot or shutdown.
3. **Node ↔ node (lattice)** — federation is authenticated by Ed25519 signatures; untrusted peers must not inject state.
4. **Process/filesystem** — UDS inherits filesystem trust; SQLite files hold workflow state; secrets must never leak to logs.
5. **LLM/agentic surfaces** — prompts and model outputs are untrusted input *and* untrusted instructions; tool invocation requires explicit policy.

---

## Surface matrix

| # | Surface | Trust assumptions | Attack categories | Covering suite(s) | Status / open gaps |
|---|---------|-------------------|-------------------|-------------------|--------------------|
| S1 | HTTP/1.1 listener (Helidon) | none — all requests hostile | slow-loris, oversized body, header floods, smuggling (CL.TE), path traversal, CRLF, null bytes, method override, Host abuse, multipart flood, connection flood | `HttpAdversarialTest` (8), extended in Phase 2 T7 | slow-loris header-read timeout = **known Helidon limitation** (4.0→4.5 verified); watchdog = Phase 2 T9. Multipart/CL.TE/header-flood added T7 |
| S2 | OIPC V3 wire (TCP) | TCP peers untrusted unless `vatn.ipc.require_auth_token` | bad magic, partial frames, oversized claimed length, bad versions, data-before-HELLO, illegal flag combos, replayed HELLO, dup msgIds, connect/disconnect floods, fuzz | `OipcWireAdversarialTest` (9), `OipcConcurrencyTest`, fuzz in T8 | solid; fuzzing added T8 |
| S3 | OIPC v2.13 Greeting + auth_token | token-bearing peers only on TCP; constant-time compare | token brute-force, timing, truncated/ambiguous Greeting, version-byte confusion | `OipcHandshakeTest`, T4 `OipcAuthAdversarialTest` | greeting read deadline-bounded (5s); adversarial token cases added T4 |
| S4 | OIPC HTTP CONNECT tunnel | allowlist = only egress policy | allowlist bypass (host casing/port tricks), malformed/oversized CONNECT, feature-off rejection | T4 `OipcAuthAdversarialTest` | added T4 (feature default-off) |
| S5 | OIPC over UDS | filesystem perms = trust | auth bypass assumptions, socket-file lifecycle | T4 case 6 | documented design: auth skipped on UDS |
| S6 | WebSocket | none — upgrades hostile | bad upgrade, oversized frames, flood, cross-site WS hijacking (Origin), rapid close | T5 `WsAdversarialTest` | **was zero coverage**; Origin posture recorded as finding in T5 |
| S7 | JWT auth (`vatn-plugin-auth`) | secret ≥32 chars, server-side validator | forgery, alg=none confusion, tampered payload, expiry, replay, header abuse, brute-force, malformed bodies | T3 `AuthAdversarialTest` | **was zero adversarial coverage** (only functional tests + 400-fix bf9e049) |
| S8 | CORS (`vatn-plugin-cors`) | origin list = only policy | origin bypass (suffix/case/trailing slash), credentials+wildcard combo, preflight abuse, null origin | T6 `CorsAdversarialTest` | **was zero tests at all** |
| S9 | Plugin packages/JARs | Ed25519 sig → RESTRICTED; unsigned → SANDBOXED; invalid → refused | malformed manifest, missing fields, path traversal in package, signature forgery | `PluginChaosMonkeyTest` (4), `LatticeSecurityTest` | solid |
| S10 | Plugin lifecycle in-process | plugin code is hostile-but-loaded | null returns, infinite init, route collisions, memory pressure, hanging shutdown, concurrent corruption | `PluginHardeningTest` (6) | hardened 9792ae2; no memory sandbox (documented) |
| S11 | DAG engine + XCom + queues | operators run with node privileges | replay/idempotency, WAL truncation, thread leaks, concurrent trigger storms, cross-run XCom leakage | `CrashRecoveryTest` (7) | solid; XCom-injection = future |
| S12 | Admin UI (`vatn-plugin-admin` + webadmin) | bearer token / JWT login | auth bypass on `/api/*`, XSS via plugin names/routes, default credentials fallback | **none today** | **open gap** → Phase 3.4+3.6; webadmin defaults fail-open with only a warning |
| S13 | Lattice federation | Ed25519-verified peers | invalid signatures, partition abuse, 100-node fan-in | `LatticeSecurityTest`, `LatticePartitionTest`, `VolumeStressTest` | baseline coverage |
| S14 | SSRF egress (`VGuardService`) | guard sees every outbound | loopback, private ranges, cloud metadata endpoints | `VSsrfGuardTest` (6) | solid |
| S15 | Agentic/LLM (`vatn-plugin-openai`, `comm`, MCP/polyglot FFI) | prompts+outputs untrusted; tools need policy | prompt injection, tool abuse, token bombs, secret leakage via model I/O | T10 `AgenticAdversarialTest` | **was zero coverage** — the dev.to article's home turf |

## Process gaps closed in Phase 2

- Adversarial suite now runs **in the default gate** (T2) — previously opt-in and effectively unrun.
- Property-based fuzzing added for frame parsing (T8) — previously hand-written cases only.
- AI adversarial code-review ritual defined (T11) with a findings log seeded by this week's real bugs.

## Explicit non-goals (documented decisions)

- No memory/CPU sandbox for in-process plugins (trust levels gate *loading*, not *execution*); `memoryHungryPluginBounded` asserts the node survives, not that the plugin is contained.
- Slow-loris header-read timeout is delegated to the Phase-2 watchdog (T9), not Helidon config.
