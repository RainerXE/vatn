/*
 * fs_guard.c — Frejay Native File-System Guard (C implementation)
 *
 * Identical ABI to the Odin version — Java FFM API binds the same way.
 * The C version serves as the universal reference: any language that can
 * produce a shared library with a C ABI (Rust, Zig, C++, Swift, etc.)
 * plugs in without changing a single line of Java.
 *
 * Compile:
 *   Linux:   gcc -shared -fPIC -O2 -o libfsguard.so  fs_guard.c
 *   macOS:   gcc -shared -fPIC -O2 -o libfsguard.dylib fs_guard.c
 *   Windows: cl /LD fs_guard.c /Fe:fsguard.dll
 */

#include <stdint.h>
#include <string.h>
#include <stdlib.h>

/* -------------------------------------------------------------------------
 * Verdict constants — must match GuardVerdict enum in Frejay-api
 * ---------------------------------------------------------------------- */
#define VERDICT_ALLOW             0
#define VERDICT_BLOCK             1
#define VERDICT_REQUIRE_APPROVAL  2

/* -------------------------------------------------------------------------
 * Wire structs — packed, blittable, mirrored by Java MemoryLayout
 * ---------------------------------------------------------------------- */
typedef struct __attribute__((packed)) {
    const char *path;       /* absolute path being accessed        */
    const char *operation;  /* "READ"|"WRITE"|"DELETE"|"LIST"|"EXECUTE" */
    const char *agent_id;   /* workspace-scoped agent id           */
    const char *workspace;  /* workspace root (agent's allowed root) */
} GuardRequest;

typedef struct __attribute__((packed)) {
    int32_t     verdict;    /* VERDICT_* constant                  */
    const char *reason;     /* static string — caller must not free */
} GuardResult;

/* -------------------------------------------------------------------------
 * Internal helpers
 * ---------------------------------------------------------------------- */

/* Returns 1 if path starts with workspace (simple prefix, no realpath). */
static int is_within_workspace(const char *path, const char *workspace) {
    if (!path || !workspace || workspace[0] == '\0') return 1;
    size_t wlen = strlen(workspace);
    return strncmp(path, workspace, wlen) == 0;
}

static const char *SENSITIVE_SUFFIXES[] = {
    ".env", ".pem", ".key", ".p12", ".pfx",
    "id_rsa", "id_ed25519", ".htpasswd",
    "secrets.yaml", "secrets.yml", NULL
};

static const char *SENSITIVE_SUBSTRINGS[] = {
    "/.ssh/", "/.gnupg/", "/etc/shadow", "/etc/passwd", NULL
};

static int has_suffix(const char *str, const char *suffix) {
    size_t sl = strlen(str), su = strlen(suffix);
    return sl >= su && strcmp(str + sl - su, suffix) == 0;
}

static int is_sensitive(const char *path) {
    if (!path) return 0;
    for (int i = 0; SENSITIVE_SUFFIXES[i]; i++)
        if (has_suffix(path, SENSITIVE_SUFFIXES[i])) return 1;
    for (int i = 0; SENSITIVE_SUBSTRINGS[i]; i++)
        if (strstr(path, SENSITIVE_SUBSTRINGS[i])) return 1;
    return 0;
}

/* -------------------------------------------------------------------------
 * Exported C-ABI functions
 * ---------------------------------------------------------------------- */

/*
 * frejay_fs_guard — primary entry point.
 * Evaluate a file-system operation and return a guard verdict.
 */
GuardResult frejay_fs_guard(const GuardRequest *req) {
    /* 1. Null-safety */
    if (!req || !req->path || !req->operation)
        return (GuardResult){ VERDICT_BLOCK, "null request fields" };

    const char *op = req->operation;

    /* 2. Workspace containment */
    if (req->workspace && req->workspace[0] != '\0') {
        if (!is_within_workspace(req->path, req->workspace))
            return (GuardResult){ VERDICT_BLOCK, "path escapes workspace boundary" };
    }

    /* 3. Sensitive-file rules */
    if (is_sensitive(req->path)) {
        if (strcmp(op, "READ") == 0)
            return (GuardResult){ VERDICT_REQUIRE_APPROVAL,
                                  "sensitive file read requires approval" };
        if (strcmp(op, "WRITE") == 0 || strcmp(op, "DELETE") == 0)
            return (GuardResult){ VERDICT_BLOCK,
                                  "write/delete of sensitive file is blocked" };
    }

    /* 4. EXECUTE always requires approval */
    if (strcmp(op, "EXECUTE") == 0)
        return (GuardResult){ VERDICT_REQUIRE_APPROVAL,
                              "file execution requires human approval" };

    /* 5. DELETE requires approval even for non-sensitive files */
    if (strcmp(op, "DELETE") == 0)
        return (GuardResult){ VERDICT_REQUIRE_APPROVAL,
                              "file deletion requires human approval" };

    /* 6. Default: allow */
    return (GuardResult){ VERDICT_ALLOW, "allowed" };
}

/*
 * frejay_fs_guard_version — capability negotiation at plugin startup.
 */
const char *frejay_fs_guard_version(void) {
    return "c-fs-guard/1.0.0";
}
