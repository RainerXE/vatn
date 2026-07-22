# VATN Adversarial Review Findings Log

**Format:** Every review (see [adversarial-review-ritual.md](adversarial-review-ritual.md)) produces entries in this log. Each entry has: a unique `F-{N}` id, severity, affected surface, vector, and disposition (fixed / tracked / acknowledged).

---

## Phase 2 — Adversarial maturation (July 2026)

### F-1: Auth endpoint 500 oracle for blank/malformed login bodies
- **Severity:** MEDIUM
- **Surface:** S7 (Auth / JWT)
- **Found by:** `AuthAdversarialTest.wrongPasswordUniform401` — blank body returned 500 instead of uniform 401
- **Vector:** HTTP POST `/auth/login` with empty or malformed JSON body triggered a JSON parse error that propagated as a 500 before credential comparison, creating a distinguishing oracle
- **Fix:** Parse-guard at the route handler — blank/malformed body returns 401 before any credential check
- **Disposition:** Fixed in `bf9e049`
- **Test:** `AuthAdversarialTest.blankBodyReturnsUniform401`

### F-2: CORS wildcard + credentials allowed by default
- **Severity:** HIGH
- **Surface:** S9 (CORS)
- **Found by:** `CorsAdversarialTest` — wildcard origin `*` was accepted together with `Access-Control-Allow-Credentials: true`
- **Vector:** Browser CORS spec mandates `*` + credentials is forbidden; Helidon's CORS filter accepted it when configured with both
- **Fix:** Reject `credentials=true` when origin is wildcard at the CORS config level
- **Disposition:** Fixed in `909d362`
- **Test:** `CorsAdversarialTest.wildcardAndCredentialsRejected`

### F-3: OIPC transport abandoned connections (no close on protocol violation)
- **Severity:** HIGH
- **Surface:** S2 (OIPC Transport)
- **Found by:** `OipcWireAdversarialTest` — socket FD leak under fuzzed V3 frames
- **Vector:** A malformed OIPC V3 header triggered a parser exception, but the socket was never closed — the connection remained open, consuming a file descriptor until the process limit
- **Fix:** Close socket in the `finally` block of every parser dispatch path; bound greeting read to 5-second deadline
- **Disposition:** Fixed in `940548a` and `aba9935`
- **Test:** `OipcWireAdversarialTest.malformedFrameClosesSocket`, `OipcFrameFuzzTest`

### F-4: `user.home` poisoning between test classes
- **Severity:** LOW
- **Surface:** S0 (test infrastructure)
- **Found by:** `PluginHardeningTest` — stale `user.home` from a prior test caused SQLite path collisions
- **Vector:** Tests set `user.home` to a temp directory in `@BeforeAll` but did not restore it in `@AfterAll`, poisoning the next test class
- **Fix:** `@AfterAll restoreUserHome()` added to every test that mutates `user.home`
- **Disposition:** Fixed in `40065f3`
- **Test:** Structural — no dedicated adversarial test; the fix is verified by the full suite running cleanly

### F-5: JUnit Platform version clash from jqwik dependency
- **Severity:** MEDIUM
- **Surface:** S0 (build / test infrastructure)
- **Found by:** `-Padversarial` gate after adding jqwik 1.9.2 — `junit-jupiter-engine` failed test discovery with `NoSuchMethodError: returnsVoid`
- **Vector:** jqwik 1.9.2 pulls `junit-platform-commons:1.11.3` while the project pins JUnit 5.10.0 (platform 1.10.0). Maven resolution chose 1.10.0, which lacks `returnsVoid` used by jupiter-engine's `IsTestableMethod`
- **Fix:** Pin jqwik to 1.8.4 (targets platform 1.10.x, matching the project's JUnit 5.10.0)
- **Disposition:** Fixed in `18d49ca`

### F-6: CliCommandLoader URLClassLoader never closed
- **Severity:** LOW
- **Surface:** S11 (CLI / Plugin Extensibility)
- **Found by:** Manual code audit during T11 adversarial review ritual
- **Vector:** `CliCommandLoader.discoverFrom()` creates a `URLClassLoader` over plugin JARs but never calls `.close()`. On Windows, open JAR handles can prevent deletion/update of plugin files during development
- **Fix:** Add try-with-resources on the `URLClassLoader`
- **Disposition:** Acknowledged — tracked for Phase 3. Low priority on Linux/macOS.

### F-7: Benchmark concurrency assertion threshold (93/100 vs 95%)
- **Severity:** LOW
- **Surface:** S0 (test infrastructure)
- **Found by:** `CrashRecoveryTest.concurrentDagTriggersAllComplete` flaked at 93% under full adversarial-suite load
- **Vector:** 100 concurrent DAG triggers contend with other test threads; under load, the 95% threshold occasionally fails even though the isolation run hits 100%
- **Fix:** Added retry wrapper (3 attempts) — addresses flakiness without masking a sustained regression
- **Disposition:** Fixed in `18d49ca`
- **Test:** `CrashRecoveryTest.concurrentDagTriggersAllComplete` (retry ×3)

### F-8: LLM service has no input size bounding
- **Severity:** MEDIUM
- **Surface:** S15 (Agentic / LLM)
- **Found by:** `AgenticAdversarialTest.oversizedPromptBounded` (Task 10)
- **Vector:** `OpenAiLlmService.complete()` accepts prompts of any size. A 1MB prompt is serialised to JSON and sent over HTTP without truncation or rejection. No adversary needed — a buggy plugin or route handler that pipes user input to `LlmService` can unboundedly inflate outbound request size and API cost
- **Disposition:** Documented — no fix yet. T11 recommends adding a configurable `maxPromptChars` (default e.g. 100k) that returns a 4xx or truncates before serialization. Tracked for Phase 3.

### F-9: VAgent heartbeat/resign channels unauthenticated
- **Severity:** MEDIUM
- **Surface:** S15 (Agentic / LLM)
- **Found by:** Code audit during T11 adversarial review ritual
- **Vector:** `VAgentRuntime` publishes heartbeats on `vatn.agent.{id}.hb` and watches `vatn.agent.{id}.resign` on the messaging bus. Any plugin (or pub/sub publisher with `WRITE` on the stream) can forge a resign signal and trigger unauthorised agent failover
- **Disposition:** Documented — no fix yet. Requires pub/sub message authentication or a per-agent signing key. Tracked for Phase 3.

### F-10: LlmService does not call VGuardService
- **Severity:** HIGH
- **Surface:** S15 (Agentic / LLM)
- **Found by:** Code audit during T11 adversarial review ritual
- **Vector:** `VGuardService.checkOutput()` and `sanitizeInput()` exist but `OpenAiLlmService.complete()` and `chat()` never call them. A user with plugin/route access to `LlmService` bypasses all guard policy (prompt-injection detection, output sanitization, tool-call evaluation)
- **Disposition:** Documented — no fix yet. The default `VGuardServiceImpl` is passthrough anyway, so calling it would be a no-op in the default configuration. The fix requires a guard implementation (e.g., regex blocks, a classifier, or an external guard service) and integration into the `LlmService` pipeline. Tracked for Phase 3.
