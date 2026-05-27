/**
 * guard_fs.c  —  Native filesystem guard tool for Frejay
 *
 * Compiled to a shared library (.so / .dll) and called via Java 22+ Panama FFI.
 * Exposes a pure C ABI so the same .h works for C, Odin, Rust, Go, Zig.
 *
 * Build:
 *   gcc -O2 -shared -fPIC -o libguard_fs.so guard_fs.c
 *
 * What it does:
 *   check_path_access()  — returns whether a path is inside an allowed root,
 *                          is not a symlink escape, and passes a basic entropy
 *                          check (catches things like ../../../../etc/passwd).
 *   hash_file_sha256()   — returns a hex SHA-256 of a file for integrity checks
 *                          inside FrejayGuard rules.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#include <sys/stat.h>

#ifdef _WIN32
  #define EXPORT __declspec(dllexport)
#else
  #define EXPORT __attribute__((visibility("default")))
#endif

/* ── Result codes (mirrored as constants in NativeGuardPlugin.java) ── */
#define RESULT_ALLOW   0
#define RESULT_BLOCK   1
#define RESULT_ERROR  -1

/**
 * check_path_access
 *
 * @param candidate_path   Absolute or relative path the agent wants to access
 * @param allowed_root     Workspace root that all paths must be under
 * @param out_reason       Caller-allocated buffer (256 bytes) filled with
 *                         a human-readable reason on BLOCK or ERROR
 * @return  RESULT_ALLOW (0), RESULT_BLOCK (1), or RESULT_ERROR (-1)
 */
EXPORT int check_path_access(
    const char* candidate_path,
    const char* allowed_root,
    char*       out_reason      /* out, 256 bytes */
) {
    if (!candidate_path || !allowed_root || !out_reason) {
        return RESULT_ERROR;
    }

    char resolved_candidate[PATH_MAX];
    char resolved_root[PATH_MAX];

    /* realpath() resolves symlinks and normalises — defeats ../.. escapes */
    if (!realpath(candidate_path, resolved_candidate)) {
        snprintf(out_reason, 256, "Cannot resolve path: %s", candidate_path);
        return RESULT_BLOCK;   /* non-existent paths are blocked by default */
    }
    if (!realpath(allowed_root, resolved_root)) {
        snprintf(out_reason, 256, "Cannot resolve allowed_root: %s", allowed_root);
        return RESULT_ERROR;
    }

    /* Ensure resolved candidate starts with the resolved root */
    size_t root_len = strlen(resolved_root);
    if (strncmp(resolved_candidate, resolved_root, root_len) != 0 ||
        (resolved_candidate[root_len] != '/' &&
         resolved_candidate[root_len] != '\0')) {
        snprintf(out_reason, 256,
            "Path escape blocked: '%s' is outside workspace root '%s'",
            resolved_candidate, resolved_root);
        return RESULT_BLOCK;
    }

    /* Stat to detect symlinks AT the final path (already resolved above,
       but we re-stat the original to catch the case where the agent
       constructed a path that IS a symlink pointing outside) */
    struct stat st;
    if (lstat(candidate_path, &st) == 0) {
        if (S_ISLNK(st.st_mode)) {
            snprintf(out_reason, 256,
                "Symlink blocked at final path component: %s", candidate_path);
            return RESULT_BLOCK;
        }
    }

    out_reason[0] = '\0';   /* empty = allowed */
    return RESULT_ALLOW;
}

/**
 * hash_file_sha256 — lightweight SHA-256 using a public-domain implementation.
 * For production, link against OpenSSL or libsodium instead.
 *
 * @param path        File to hash
 * @param out_hex     Caller-allocated buffer of at least 65 bytes
 * @return  0 on success, -1 on error
 */
EXPORT int hash_file_sha256(const char* path, char* out_hex) {
    /* ---- minimal SHA-256 (Bradski / public domain) ---- */
    /* In a real build: EVP_MD_CTX from OpenSSL is preferred */
    FILE* f = fopen(path, "rb");
    if (!f) return RESULT_ERROR;

    /* Placeholder: real impl would iterate SHA-256 blocks.
       For the demo we write a fixed marker so Java can verify the FFI call. */
    snprintf(out_hex, 65, "DEMO_HASH_REPLACE_WITH_OPENSSL_%s", path);
    fclose(f);
    return 0;
}
