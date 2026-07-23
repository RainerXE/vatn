# AI Adversarial Review Ritual

A **recurring, mandatory** process for every significant PR in VATN (new feature, surface addition, or security-related change). The goal is to catch weaknesses before users do — the same method that found every bug tracked in the [findings log](findings-log.md).

---

## Trigger

Run this ritual when a PR touches any of:

- A new plugin or SPI (`vatn-api`)
- A new CLI command, service, or transport (`vatn-core`, `vatn-cli`)
- An agent or LLM integration (`vatn-plugin-openai`, `vatn-plugin-comm`)
- A polyglot FFI bridge or OIPC protocol handler
- Any code path that parses, forwards, or stores untrusted input

---

## Steps

### Step 1 — Context loading

The reviewer reads (or is given) the PR's diff, the relevant [surface row](#) in `docs/threat-model.md`, and the [latest findings log](findings-log.md).

### Step 2 — Attacker-mindset AI prompt (template)

Copy the following into an LLM session with the PR diff attached:

```
You are a red-teamer reviewing a patch for the VATN distributed-application runtime.
Your task: find security weaknesses the author did not anticipate.

## Threat surfaces from docs/threat-model.md relevant to this PR

{list surface rows here — S1, S7, …}

## Output format

For each finding, produce a 5-line block:

F-{N}: {short name}
  Severity: CRITICAL | HIGH | MEDIUM | LOW | INFO
  Surface: S{N}
  Vector: {one-liner of how the attack works}
  Code path: {file:line range}
  Fix: {one-liner of the remediation}

Then summarise: how many findings per severity, and whether any are
blocking. If there are zero findings, say so explicitly.
```

### Step 3 — Triage

The human reviewer triages each finding:

| Severity | Definition | SLA |
|----------|-----------|-----|
| CRITICAL | Remote code execution, secret exfiltration, unauthenticated full-node access | Fix before merge |
| HIGH | Denial of service, privilege escalation, data corruption | Fix before merge |
| MEDIUM | Observable oracle, resource leak, partial information disclosure | Plan within 1 sprint |
| LOW | Verbose logging, minor hardening gap | Log in findings, fix opportunistically |
| INFO | Design trade-off acknowledged and documented | No action |

### Step 4 — TDD conversion rule

**Every confirmed finding becomes an adversarial test BEFORE the fix lands.**

1. Write a failing test that reproduces the vulnerability (in `vatn-verify/.../verify/`, tagged `@Tag("adversarial")`).
2. Fix the code.
3. Confirm the test passes.

This mirrors the pattern already used in this branch: bugs found by the adversarial gate (`auth 500 oracle`, `CORS wildcard+credentials`, `transport abandoned connections`) were first captured as tests.

### Step 5 — Findings log

Every review (empty or not) is recorded in [docs/security/findings-log.md](findings-log.md). A zero-finding review proves nothing — the finding is "surface reviewed: no issues found."

---

## Timeline

- **All PRs:** must pass the adversarial suite (`-Padversarial`) and the review ritual if they touch a flagged surface.
- **Quarterly:** full surface review across every row of the threat-model matrix. The findings log is updated with each review.
- **Post-incident:** any externally reported vulnerability triggers an immediate full review of the affected surface and all adjacent surfaces, with the incident appended to the findings log.
