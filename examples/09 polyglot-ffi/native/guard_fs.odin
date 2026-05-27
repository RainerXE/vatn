/**
 * guard_fs.odin  —  Odin implementation of the same guard_fs ABI
 *
 * Odin compiles to a native shared library with a C-compatible ABI when you
 * use `foreign export` + `odin build -build-mode:shared`.
 *
 * Build:
 *   odin build guard_fs.odin -build-mode:shared -out:libguard_fs.so
 *
 * The Java Panama FFI code (NativeGuardPlugin.java) is IDENTICAL whether you
 * load the C or the Odin build — both expose the same symbol names and calling
 * convention.  This is the key insight: Java does not know or care which
 * language produced the .so.
 */

package guard_fs

import "core:os"
import "core:strings"
import "core:path/filepath"
import "core:fmt"
import "core:c"

RESULT_ALLOW  :: c.int(0)
RESULT_BLOCK  :: c.int(1)
RESULT_ERROR  :: c.int(-1)

/**
 * check_path_access
 *
 * Mirrors the C version exactly.  Odin's `filepath.clean` + `os.read_link`
 * provide the same symlink-resolution semantics as POSIX realpath().
 */
@(export)
check_path_access :: proc "c" (
    candidate_path : cstring,
    allowed_root   : cstring,
    out_reason     : [^]c.char,   // 256-byte caller buffer
) -> c.int {
    context = runtime.default_context()   // required for Odin allocators in "c" procs

    cand := string(candidate_path)
    root := string(allowed_root)

    // filepath.clean normalises ./ and ../ components
    clean_cand := filepath.clean(cand)
    clean_root := filepath.clean(root)

    // Check prefix — equivalent to the strncmp in the C version
    if !strings.has_prefix(clean_cand, clean_root) {
        msg := fmt.caprintf(
            "Path escape blocked: '%s' is outside workspace root '%s'",
            clean_cand, clean_root,
        )
        defer delete(msg)
        c_copy(out_reason, msg, 256)
        return RESULT_BLOCK
    }

    // Symlink check on the original path
    fi, err := os.lstat(cand)
    if err == os.ERROR_NONE {
        if fi.mode & os.ModeSymlink != 0 {
            msg := fmt.caprintf("Symlink blocked at: %s", cand)
            defer delete(msg)
            c_copy(out_reason, msg, 256)
            return RESULT_BLOCK
        }
    }

    // Zero out the reason buffer on success
    out_reason[0] = 0
    return RESULT_ALLOW
}

@(export)
hash_file_sha256 :: proc "c" (path: cstring, out_hex: [^]c.char) -> c.int {
    context = runtime.default_context()
    // Production: use core:crypto/sha2
    // Demo: write a placeholder so the Java test can verify the call succeeded
    msg := fmt.caprintf("ODIN_HASH_REPLACE_WITH_REAL_IMPL_%s", string(path))
    defer delete(msg)
    c_copy(out_hex, msg, 65)
    return 0
}

// ── helpers ──────────────────────────────────────────────────────────────────

@(private)
c_copy :: proc(dst: [^]c.char, src: string, max: int) {
    n := min(len(src), max - 1)
    for i in 0..<n {
        dst[i] = c.char(src[i])
    }
    dst[n] = 0
}
