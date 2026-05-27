package dev.vatn.examples.ffi;

import dev.vatn.examples.ffi.c.VatnTextTool;
import dev.vatn.examples.ffi.odin.VatnFsGuardTool;
import dev.vatn.examples.ffi.python.VatnPythonAnalysisTool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * VatnFfiDemo
 *
 * Self-contained demo runner for all three FFI strategies.
 * Run after building the native libraries:
 *
 *   # Build C library
 *   gcc -O2 -shared -fPIC -o native/libvatn_text_tool.so c-tool/vatn_text_tool.c
 *
 *   # Build Odin library
 *   odin build odin-tool/vatn_fs_guard.odin -file -build-mode:shared -out:native/libvatn_fs_guard.so
 *
 *   # Run demo
 *   mvn compile exec:java -Dexec.mainClass="dev.vatn.examples.ffi.VatnFfiDemo"
 *
 * The NATIVE_DIR system property (default: ./native) tells the demo where to
 * find the compiled shared libraries.
 *
 * NOTE: If native libs are not present, the C and Odin demos are skipped
 *       gracefully — the Python demo always runs if Python is on PATH.
 */
public class VatnFfiDemo {

    public static void main(String[] args) {
        Path nativeDir  = Paths.get(System.getProperty("NATIVE_DIR", "native")).toAbsolutePath();
        Path scriptDir  = Paths.get("python-tool").toAbsolutePath();

        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  Vatn FFI Demo  —  Three Foreign Tool Strategies");
        System.out.println("═══════════════════════════════════════════════════════\n");

        // ── 1. C tool via FFM ────────────────────────────────────────────
        System.out.println("── 1. C native tool (libvatn_text_tool) via Java 25 FFM ──");
        try {
            VatnTextTool textTool = new VatnTextTool(nativeDir);

            var r1 = textTool.execute(Map.of(
                    "action", "count_occurrences",
                    "text",   "the quick brown fox jumps over the lazy dog the end",
                    "needle", "the"
            ));
            System.out.println("  count_occurrences('the') → " + r1);

            var r2 = textTool.execute(Map.of(
                    "action", "to_upper",
                    "text",   "Hello, Vatn World!"
            ));
            System.out.println("  to_upper            → " + r2);

            var r3 = textTool.execute(Map.of(
                    "action", "line_count",
                    "text",   "line one\nline two\nline three\n"
            ));
            System.out.println("  line_count          → " + r3);

        } catch (UnsatisfiedLinkError e) {
            System.out.println("  [SKIP] libvatn_text_tool not found in " + nativeDir);
            System.out.println("         Build with: gcc -O2 -shared -fPIC -o " +
                    nativeDir + "/libvatn_text_tool.so c-tool/vatn_text_tool.c");
        }

        System.out.println();

        // ── 2. Odin tool via FFM ─────────────────────────────────────────
        System.out.println("── 2. Odin native tool (libvatn_fs_guard) via Java 25 FFM ──");
        try {
            VatnFsGuardTool guardTool = new VatnFsGuardTool(nativeDir);
            Path wsRoot = Paths.get(System.getProperty("user.home"), "workspace");

            String[][] testPaths = {
                    { wsRoot + "/src/Main.java",      "normal source file"     },
                    { wsRoot + "/../etc/passwd",       "traversal attempt"      },
                    { wsRoot + "/config/secrets.env",  "blocked extension"      },
                    { wsRoot + "/data/report.pdf",     "normal data file"       },
            };

            for (String[] test : testPaths) {
                var result = guardTool.execute(Map.of(
                        "path",           test[0],
                        "workspace_root", wsRoot.toString()
                ));
                System.out.printf("  %-40s → %s%n", test[1], result.get("verdict"));
            }

        } catch (UnsatisfiedLinkError e) {
            System.out.println("  [SKIP] libvatn_fs_guard not found in " + nativeDir);
            System.out.println("         Build with: odin build odin-tool/vatn_fs_guard.odin " +
                    "-file -build-mode:shared -out:" + nativeDir + "/libvatn_fs_guard.so");
        }

        System.out.println();

        // ── 3. Python tool via ProcessBuilder ────────────────────────────
        System.out.println("── 3. Python tool (vatn_analysis_tool.py) via ProcessBuilder stdio ──");
        try {
            VatnPythonAnalysisTool pyTool = new VatnPythonAnalysisTool(scriptDir);

            String sampleText = """
                    Vatn is an excellent Java agent platform. It is fast, easy to extend,
                    and integrates brilliantly with native code. Building plugins is wonderful.
                    The FFI support is outstanding and makes Vatn a fantastic choice.
                    """;

            var r1 = pyTool.execute(Map.of(
                    "action", "sentiment",
                    "params", Map.of("text", sampleText)
            ));
            System.out.println("  sentiment      → " + r1);

            var r2 = pyTool.execute(Map.of(
                    "action", "word_frequency",
                    "params", Map.of("text", sampleText, "top_n", 5)
            ));
            System.out.println("  word_frequency → " + r2);

            var r3 = pyTool.execute(Map.of(
                    "action", "summarise",
                    "params", Map.of("text", sampleText, "sentences", 2)
            ));
            System.out.println("  summarise      → " + r3);

        } catch (Exception e) {
            System.out.println("  [SKIP] Python not available or script error: " + e.getMessage());
        }

        System.out.println("\n═══════════════════════════════════════════════════════");
        System.out.println("  Demo complete.");
        System.out.println("═══════════════════════════════════════════════════════");
    }
}
