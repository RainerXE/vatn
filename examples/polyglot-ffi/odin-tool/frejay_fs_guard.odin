/*
 * frejay_fs_guard.odin
 *
 * Frejay FFI Demo — Odin native filesystem guard tool
 * Exported with a C ABI so the Java 25 FFM API can call it identically to C.
 *
 * Use-case: Path-level FrejayGuard integration — before the agent reads or
 * writes a file, call frejay_path_verdict() to get a platform-native verdict
 * that considers OS permissions, path traversal, and workspace boundaries
 * in a way that is faster and more reliable than anything doable in pure Java.
 *
 * Build (Linux):
 *   odin build frejay_fs_guard.odin -file -build-mode:shared -out:libfrejay_fs_guard.so
 *
 * Build (macOS):
 *   odin build frejay_fs_guard.odin -file -build-mode:shared -out:libfrejay_fs_guard.dylib
 *
 * Build (Windows):
 *   odin build frejay_fs_guard.odin -file -build-mode:shared -out:frejay_fs_guard.dll
 *
 * Verdict constants (mirrored in FrejayFsGuardTool.java):
 *   0 = ALLOW
 *   1 = BLOCK_TRAVERSAL       path escapes workspace root
 *   2 = BLOCK_SYMLINK         path resolves through a symlink (policy: deny)
 *   3 = BLOCK_PERMISSION      OS says the process cannot access this path
 *   4 = BLOCK_EXTENSION       file extension on deny-list (.env, id_rsa …)
 *   5 = REQUIRE_APPROVAL      path is in a sensitive zone (needs human OK)
 */

package frejay_fs_guard

import "core:os"
import "core:strings"
import "core:path/filepath"

// ── deny-listed extensions ────────────────────────────────────────────────

BLOCKED_EXTENSIONS :: []string {
    ".env", ".pem", ".key", ".p12", ".pfx",
    "id_rsa", "id_ed25519", ".aws", ".netrc",
}

SENSITIVE_EXTENSIONS :: []string {
    ".shadow", ".passwd", ".htpasswd", ".npmrc", ".pypirc",
}

// ── exported functions ────────────────────────────────────────────────────

/**
 * Evaluate whether the agent may access `path` inside `workspace_root`.
 *
 * Both arguments are null-terminated UTF-8 C strings (so Java FFM can pass
 * them as MemorySegment pointers obtained from Arena.allocateUtf8String()).
 *
 * Returns one of the verdict constants above (i32).
 */
@(export, link_name="frejay_path_verdict")
frejay_path_verdict :: proc "c" (path_cstr: cstring, workspace_root_cstr: cstring) -> i32 {
    path       := string(path_cstr)
    ws_root    := string(workspace_root_cstr)

    // 1. Resolve to absolute path (handles ".." traversal)
    abs_path, abs_ok := filepath.abs(path)
    if !abs_ok do return 1   // can't resolve → treat as traversal

    abs_root, root_ok := filepath.abs(ws_root)
    if !root_ok do return 1

    // 2. Traversal check — resolved path must start with workspace root
    if !strings.has_prefix(abs_path, abs_root) {
        return 1 // BLOCK_TRAVERSAL
    }

    // 3. Extension deny-list
    ext := filepath.ext(abs_path)
    base := filepath.base(abs_path)

    for blocked in BLOCKED_EXTENSIONS {
        if ext == blocked || base == blocked {
            return 4 // BLOCK_EXTENSION
        }
    }
    for sensitive in SENSITIVE_EXTENSIONS {
        if ext == sensitive || base == sensitive {
            return 5 // REQUIRE_APPROVAL
        }
    }

    // 4. Symlink check
    fi, fi_err := os.lstat(abs_path)
    if fi_err == os.ERROR_NONE {
        if fi.mode & os.ModeSymlink != 0 {
            return 2 // BLOCK_SYMLINK
        }
    }

    // 5. OS permission check (attempt to open for reading)
    fd, open_err := os.open(abs_path, os.O_RDONLY)
    if open_err != os.ERROR_NONE {
        return 3 // BLOCK_PERMISSION
    }
    os.close(fd)

    return 0 // ALLOW
}

/**
 * Returns a human-readable label for a verdict code.
 * The returned pointer is a static string literal — do NOT free it.
 */
@(export, link_name="frejay_verdict_label")
frejay_verdict_label :: proc "c" (verdict: i32) -> cstring {
    switch verdict {
    case 0: return "ALLOW"
    case 1: return "BLOCK_TRAVERSAL"
    case 2: return "BLOCK_SYMLINK"
    case 3: return "BLOCK_PERMISSION"
    case 4: return "BLOCK_EXTENSION"
    case 5: return "REQUIRE_APPROVAL"
    case:   return "UNKNOWN"
    }
}
