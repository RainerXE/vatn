/**
 * VATN (Virtual Application Transaction Nodes) Stable C-ABI
 * Milestone v0.3a — The Native Diamond
 * 
 * This header defines the binary interface for embedding VATN into native 
 * applications (C, C++, Rust, etc.).
 * 
 * Design Principles:
 * 1. Opaque Handles: Java state is hidden behind VNodeHandle pointers.
 * 2. Length-Prefixing: All binary data is passed via the VBuffer struct.
 * 3. Memory Ownership: "The Allocator Frees". Library allocates responses; 
 *    Host calls vatn_free_buffer() to reclaim them.
 */

#ifndef VATN_H
#define VATN_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * A memory-safe buffer structure for cross-language binary transport.
 * Used for OIPC V2.12 frames.
 */
typedef struct {
    uint8_t* data;
    uint32_t size;
} VBuffer;

/**
 * An opaque handle to a VATN Node instance.
 */
typedef void* VNodeHandle;

/**
 * Initializes the VATN runtime and starts a node.
 * 
 * @param config_json A JSON string defining the node configuration.
 * @return A handle to the started node, or NULL on failure.
 */
VNodeHandle vatn_node_start(const char* config_json);

/**
 * Stops a VATN node and releases its resources.
 * 
 * @param handle The node handle to shutdown.
 */
void vatn_node_stop(VNodeHandle handle);

/**
 * The Uniform Gateway — The binary-stable entry point for all VATN services.
 * 
 * Matches the OIPC protocol symmetry. Every call is a binary frame in,
 * and a binary frame out.
 * 
 * @param handle The node handle.
 * @param service The logical service path (e.g., "vatn.core.db").
 * @param method The method to invoke (e.g., "query").
 * @param payload The OIPC request frame.
 * @return The OIPC response frame. Callers MUST free this via vatn_free_buffer.
 */
VBuffer vatn_call(VNodeHandle handle, const char* service, const char* method, VBuffer payload);

/**
 * Frees a VBuffer that was allocated by the VATN library.
 */
void vatn_free_buffer(VBuffer buffer);

/**
 * Configuration & Debugging
 */

/**
 * Toggles global ABI trace logging to stdout.
 */
void vatn_set_debug(bool enabled);

/**
 * Returns the last error message from the Java runtime for the current thread.
 * The returned string is managed by the library and should not be freed.
 */
const char* vatn_get_last_error();

/**
 * Returns a JSON-formatted diagnostic dump of the VATN runtime status.
 */
VBuffer vatn_get_diagnostics(VNodeHandle handle);

#ifdef __cplusplus
}
#endif

#endif // VATN_H
