package dev.vatn.examples.ffi.c;

import dev.vatn.examples.ffi.NativeLibLoader;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Map;

/**
 * VatnTextTool  —  C native tool wrapped as a Vatn @AgentPlugin
 *
 * Calls three functions from libvatn_text_tool.so via the Java 25 FFM API:
 *   vatn_count_occurrences(char*, char*) → int32
 *   vatn_to_upper_inplace(char*, int32) → int32
 *   vatn_line_count(char*) → int32
 *
 * The agent loop calls execute(Map<String,Object>) just like any other tool.
 * There is no JNI, no generated header, no glue C code.
 *
 * ── FFM crash course ─────────────────────────────────────────────────────
 *
 *  Linker        — knows the platform C ABI (argument passing, return values)
 *  FunctionDescriptor — the Java-side description of the C function signature
 *  MethodHandle  — the callable reference to the native function
 *  Arena         — allocates off-heap memory with a defined lifetime
 *                  (use confined for per-call, shared for multi-thread, global forever)
 *  MemorySegment — a pointer + length pair; wraps off-heap memory safely
 *
 * ─────────────────────────────────────────────────────────────────────────
 */
// In real Vatn: @AgentPlugin(id = "native-text-tool", version = "1.0.0")
public class VatnTextTool /* implements AgentPlugin */ {

    // ── native method handles (looked up once at construction) ────────────

    private final MethodHandle mhCountOccurrences;
    private final MethodHandle mhToUpperInplace;
    private final MethodHandle mhLineCount;

    /**
     * @param libDir  directory containing libvatn_text_tool.so/.dylib/.dll
     *                (typically next to the plugin JAR)
     */
    public VatnTextTool(Path libDir) {
        SymbolLookup lib    = NativeLibLoader.loadFromDir(libDir, "vatn_text_tool");
        Linker        linker = NativeLibLoader.linker();

        // int32_t vatn_count_occurrences(char *haystack, char *needle)
        mhCountOccurrences = linker.downcallHandle(
                lib.find("vatn_count_occurrences").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,          // return: int32_t
                        ValueLayout.ADDRESS,           // haystack: char*
                        ValueLayout.ADDRESS            // needle: char*
                )
        );

        // int32_t vatn_to_upper_inplace(char *buf, int32_t len)
        mhToUpperInplace = linker.downcallHandle(
                lib.find("vatn_to_upper_inplace").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT
                )
        );

        // int32_t vatn_line_count(char *text)
        mhLineCount = linker.downcallHandle(
                lib.find("vatn_line_count").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS
                )
        );
    }

    // ── AgentPlugin interface ─────────────────────────────────────────────

    // @Override
    public String getId() { return "native-text-tool"; }

    // @Override
    public String getDescription() {
        return """
               High-throughput native text operations backed by a C shared library.
               Actions: count_occurrences, to_upper, line_count.
               """;
    }

    /**
     * Entry point called by Vatn's ToolDispatcher.
     *
     * Expected params map keys (action = "count_occurrences"):
     *   "text"   → String haystack
     *   "needle" → String needle
     *
     * Expected params map keys (action = "to_upper"):
     *   "text"   → String to uppercase
     *
     * Expected params map keys (action = "line_count"):
     *   "text"   → String to count lines in
     */
    // @Override
    public Map<String, Object> execute(Map<String, Object> params) {
        String action = (String) params.getOrDefault("action", "count_occurrences");
        return switch (action) {
            case "count_occurrences" -> countOccurrences(params);
            case "to_upper"          -> toUpper(params);
            case "line_count"        -> lineCount(params);
            default -> Map.of("error", "unknown action: " + action);
        };
    }

    // ── action implementations ────────────────────────────────────────────

    private Map<String, Object> countOccurrences(Map<String, Object> params) {
        String haystack = (String) params.getOrDefault("text", "");
        String needle   = (String) params.getOrDefault("needle", "");

        // Arena.ofConfined() = per-call off-heap memory, freed at end of try block.
        // This is the standard FFM pattern for short-lived C strings.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msHaystack = arena.allocateFrom(haystack);
            MemorySegment msNeedle   = arena.allocateFrom(needle);

            int count = (int) mhCountOccurrences.invoke(msHaystack, msNeedle);
            return Map.of(
                    "action",      "count_occurrences",
                    "needle",      needle,
                    "occurrences", count
            );
        } catch (Throwable t) {
            return Map.of("error", t.getMessage());
        }
    }

    private Map<String, Object> toUpper(Map<String, Object> params) {
        String text = (String) params.getOrDefault("text", "");
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try (Arena arena = Arena.ofConfined()) {
            // Allocate a mutable buffer large enough for the string + null terminator
            MemorySegment buf = arena.allocate(bytes.length + 1);
            buf.asByteBuffer().put(bytes);           // copy Java bytes → off-heap
            buf.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0); // null-terminate

            int changed = (int) mhToUpperInplace.invoke(buf, bytes.length);

            // Read back the modified bytes
            byte[] result = buf.asSlice(0, bytes.length).toArray(ValueLayout.JAVA_BYTE);
            String upper = new String(result, java.nio.charset.StandardCharsets.UTF_8);

            return Map.of(
                    "action",         "to_upper",
                    "result",         upper,
                    "chars_changed",  changed
            );
        } catch (Throwable t) {
            return Map.of("error", t.getMessage());
        }
    }

    private Map<String, Object> lineCount(Map<String, Object> params) {
        String text = (String) params.getOrDefault("text", "");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msText = arena.allocateFrom(text);
            int lines = (int) mhLineCount.invoke(msText);
            return Map.of(
                    "action", "line_count",
                    "lines",  lines
            );
        } catch (Throwable t) {
            return Map.of("error", t.getMessage());
        }
    }
}
