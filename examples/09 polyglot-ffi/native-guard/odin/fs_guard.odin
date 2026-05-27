// fs_guard.odin
// VATN Native File-System Guard — Odin implementation
//
// Compile to shared library (C ABI):
//   odin build . -build-mode:shared -out:libfsguard.so   (Linux)
//   odin build . -build-mode:shared -out:libfsguard.dylib (macOS)
//   odin build . -build-mode:shared -out:fsguard.dll     (Windows)
//
// Odin exports C-ABI symbols when annotated with @(export) and
// @(link_name="...").  No JNI glue needed — Java 22+ FFM API binds directly.

package fs_guard

import "core:fmt"
import "core:os"
import "core:strings"
import "core:path/filepath"
import "core:c"

// ---------------------------------------------------------------------------
// Verdict constants — must match GuardVerdict enum in vatn-api
// ---------------------------------------------------------------------------
VERDICT_ALLOW            : c.int : 0
VERDICT_BLOCK            : c.int : 1
VERDICT_REQUIRE_APPROVAL : c.int : 2

// ---------------------------------------------------------------------------
// GuardRequest — flat struct, blittable across the FFI boundary.
// Java MemoryLayout must mirror this exactly (see FsGuardBridge.java).
// All strings are null-terminated UTF-8 pointers.
// ---------------------------------------------------------------------------
GuardRequest :: struct #packed {
    path       : cstring,  // absolute path being accessed
    operation  : cstring,  // "READ" | "WRITE" | "DELETE" | "LIST" | "EXECUTE"
    agent_id   : cstring,  // workspace-scoped agent identifier
    workspace  : cstring,  // workspace root path (agent's allowed root)
}

// GuardResult — returned by value; Java reads the two fields via MemoryLayout.
GuardResult :: struct #packed {
    verdict : c.int,    // VERDICT_* constant above
    reason  : cstring,  // static string — no heap allocation needed
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

// is_within_workspace checks that `path` is a sub-path of `workspace`.
// Simple string prefix check after cleaning both paths.
@(private)
is_within_workspace :: proc(path: cstring, workspace: cstring) -> bool {
    p  := filepath.clean(string(path))
    ws := filepath.clean(string(workspace))
    return strings.has_prefix(p, ws)
}

// is_sensitive detects well-known sensitive file patterns.
@(private)
is_sensitive :: proc(path: cstring) -> bool {
    p := string(path)
    sensitive_suffixes := []string{
        ".env", ".pem", ".key", ".p12", ".pfx",
        "id_rsa", "id_ed25519", ".htpasswd",
        "secrets.yaml", "secrets.yml",
    }
    sensitive_dirs := []string{
        "/.ssh/", "/.gnupg/", "/etc/shadow", "/etc/passwd",
    }
    for s in sensitive_suffixes {
        if strings.has_suffix(p, s) do return true
    }
    for d in sensitive_dirs {
        if strings.contains(p, d) do return true
    }
    return false
}

// ---------------------------------------------------------------------------
// Exported C-ABI function — primary entry point called from Java FFM API
// ---------------------------------------------------------------------------

// vatn_fs_guard evaluates a file-system operation and returns a verdict.
// Java calls this via MethodHandle obtained from Linker.downcallHandle().
@(export, link_name="vatn_fs_guard")
vatn_fs_guard :: proc "c" (req: ^GuardRequest) -> GuardResult {
    // 1. Null-safety: reject malformed requests
    if req == nil || req.path == nil || req.operation == nil {
        return GuardResult{verdict = VERDICT_BLOCK, reason = "null request fields"}
    }

    op := string(req.operation)

    // 2. Workspace containment — hard block if path escapes the workspace
    if req.workspace != nil && len(string(req.workspace)) > 0 {
        if !is_within_workspace(req.path, req.workspace) {
            return GuardResult{
                verdict = VERDICT_BLOCK,
                reason  = "path escapes workspace boundary",
            }
        }
    }

    // 3. Sensitive-file rules
    if is_sensitive(req.path) {
        switch op {
        case "READ":
            // Reading sensitive files requires human approval
            return GuardResult{
                verdict = VERDICT_REQUIRE_APPROVAL,
                reason  = "sensitive file read requires approval",
            }
        case "WRITE", "DELETE":
            // Writing / deleting sensitive files is always blocked
            return GuardResult{
                verdict = VERDICT_BLOCK,
                reason  = "write/delete of sensitive file is blocked",
            }
        }
    }

    // 4. EXECUTE verdict — always require approval regardless of path
    if op == "EXECUTE" {
        return GuardResult{
            verdict = VERDICT_REQUIRE_APPROVAL,
            reason  = "file execution requires human approval",
        }
    }

    // 5. DELETE requires approval even for non-sensitive files
    if op == "DELETE" {
        return GuardResult{
            verdict = VERDICT_REQUIRE_APPROVAL,
            reason  = "file deletion requires human approval",
        }
    }

    // 6. Default: allow
    return GuardResult{verdict = VERDICT_ALLOW, reason = "allowed"}
}

// vatn_fs_guard_version returns the guard implementation version string.
// Used by FsGuardBridge for capability negotiation at startup.
@(export, link_name="vatn_fs_guard_version")
vatn_fs_guard_version :: proc "c" () -> cstring {
    return "odin-fs-guard/1.0.0"
}
