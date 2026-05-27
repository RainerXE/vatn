package dev.vatn.examples.ffi.odin;

import dev.vatn.examples.ffi.NativeLibLoader;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Map;

/**
 * VatnFsGuardTool  —  Odin native filesystem guard wrapped as a Vatn plugin
 *
 * Calls two functions from libvatn_fs_guard.so (compiled from Odin with C ABI):
 *   vatn_path_verdict(char *path, char *workspace_root) → int32
 *   vatn_verdict_label(int32 verdict)                   → char* (static, do NOT free)
 *
 * This tool integrates with VatnGuard: before the agent loop dispatches a
 * FILE_READ or FILE_WRITE tool call, VatnGuard can invoke this tool's
 * evaluate() method to get a native-speed, OS-aware verdict.
 *
 * The Odin library exports with a C ABI (@export, link_name="...") so the
 * Java FFM API treats it exactly like a C library — no special handling needed.
 *
 * Verdict constants (mirror of vatn_fs_guard.odin):
 */
public class VatnFsGuardTool /* implements AgentPlugin */ {

    public enum Verdict {
        ALLOW(0),
        BLOCK_TRAVERSAL(1),
        BLOCK_SYMLINK(2),
        BLOCK_PERMISSION(3),
        BLOCK_EXTENSION(4),
        REQUIRE_APPROVAL(5),
        UNKNOWN(-1);

        public final int code;
        Verdict(int code) { this.code = code; }

        static Verdict fromCode(int code) {
            for (Verdict v : values()) if (v.code == code) return v;
            return UNKNOWN;
        }
    }

    // ── native method handles ─────────────────────────────────────────────

    private final MethodHandle mhPathVerdict;
    private final MethodHandle mhVerdictLabel;

    /**
     * @param libDir  directory containing libvatn_fs_guard.so/.dylib/.dll
     */
    public VatnFsGuardTool(Path libDir) {
        SymbolLookup lib    = NativeLibLoader.loadFromDir(libDir, "vatn_fs_guard");
        Linker        linker = NativeLibLoader.linker();

        // int32_t vatn_path_verdict(char *path, char *workspace_root)
        mhPathVerdict = linker.downcallHandle(
                lib.find("vatn_path_verdict").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,   // return: i32 verdict code
                        ValueLayout.ADDRESS,    // path: cstring (char*)
                        ValueLayout.ADDRESS     // workspace_root: cstring (char*)
                )
        );

        // cstring vatn_verdict_label(int32 verdict)  — returns static char*
        mhVerdictLabel = linker.downcallHandle(
                lib.find("vatn_verdict_label").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,    // return: char* (static string)
                        ValueLayout.JAVA_INT    // verdict code
                )
        );
    }

    // ── public API ────────────────────────────────────────────────────────

    /**
     * Evaluate a path against the workspace root.
     * Called by VatnGuard before any FILE_READ / FILE_WRITE tool dispatch.
     *
     * @param path          the path the agent wants to access
     * @param workspaceRoot the root the agent is allowed to operate within
     * @return              typed verdict
     */
    public Verdict evaluate(Path path, Path workspaceRoot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msPath = arena.allocateFrom(path.toString());
            MemorySegment msRoot = arena.allocateFrom(workspaceRoot.toString());

            int code = (int) mhPathVerdict.invoke(msPath, msRoot);
            return Verdict.fromCode(code);
        } catch (Throwable t) {
            // Fail safe: if the native call throws, block the access
            return Verdict.BLOCK_PERMISSION;
        }
    }

    /**
     * Human-readable label for a verdict, fetched from the native library.
     * The Odin function returns a pointer to a static string literal — we
     * must NOT free it, which is exactly what Arena.global() semantics give us.
     */
    public String verdictLabel(Verdict verdict) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) mhVerdictLabel.invoke(verdict.code);
            // reinterpret as null-terminated C string and copy into Java String
            return result.reinterpret(256).getString(0);
        } catch (Throwable t) {
            return "UNKNOWN";
        }
    }

    // ── AgentPlugin interface ─────────────────────────────────────────────

    // @Override
    public String getId() { return "native-fs-guard"; }

    // @Override
    public Map<String, Object> execute(Map<String, Object> params) {
        String pathStr = (String) params.getOrDefault("path", "");
        String rootStr = (String) params.getOrDefault("workspace_root", "");

        if (pathStr.isBlank() || rootStr.isBlank()) {
            return Map.of("error", "path and workspace_root are required");
        }

        Verdict verdict = evaluate(Path.of(pathStr), Path.of(rootStr));
        String  label   = verdictLabel(verdict);

        return Map.of(
                "path",            pathStr,
                "workspace_root",  rootStr,
                "verdict_code",    verdict.code,
                "verdict",         label,
                "allowed",         verdict == Verdict.ALLOW,
                "needs_approval",  verdict == Verdict.REQUIRE_APPROVAL
        );
    }
}
