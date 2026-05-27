/**
 * vatn_text_tool.c
 *
 * VATN FFI Demo — C native tool
 * Compiled as a shared library (.so / .dll) and called via Java 25 FFM API.
 *
 * Use-case: High-throughput text processing that benefits from native speed
 * (e.g. scanning large log files, tokenising, pattern counting).
 *
 * Build (Linux/macOS):
 *   gcc -O2 -shared -fPIC -o libvatn_text_tool.so vatn_text_tool.c
 *
 * Build (Windows):
 *   cl /O2 /LD vatn_text_tool.c /Fe:vatn_text_tool.dll
 */

#include <stdint.h>
#include <string.h>
#include <ctype.h>

/* ── exported symbols ─────────────────────────────────────────────────────── */

/**
 * Count occurrences of `needle` inside `haystack`.
 * Both strings are null-terminated UTF-8.
 * Returns -1 if either pointer is NULL.
 */
int32_t vatn_count_occurrences(const char *haystack, const char *needle) {
    if (!haystack || !needle) return -1;

    size_t nlen = strlen(needle);
    if (nlen == 0) return 0;

    int32_t count = 0;
    const char *p = haystack;
    while ((p = strstr(p, needle)) != NULL) {
        ++count;
        p += nlen;
    }
    return count;
}

/**
 * In-place ASCII uppercase of `buf` (length `len`).
 * Safe to call with len == 0.
 * Returns the number of characters changed.
 */
int32_t vatn_to_upper_inplace(char *buf, int32_t len) {
    if (!buf || len <= 0) return 0;
    int32_t changed = 0;
    for (int32_t i = 0; i < len; i++) {
        char c = buf[i];
        char u = (char) toupper((unsigned char) c);
        if (u != c) { buf[i] = u; ++changed; }
    }
    return changed;
}

/**
 * Count lines (newline characters + 1) in `text`.
 * Returns 0 for empty/NULL input.
 */
int32_t vatn_line_count(const char *text) {
    if (!text || *text == '\0') return 0;
    int32_t lines = 1;
    while (*text) {
        if (*text == '\n') ++lines;
        ++text;
    }
    return lines;
}
