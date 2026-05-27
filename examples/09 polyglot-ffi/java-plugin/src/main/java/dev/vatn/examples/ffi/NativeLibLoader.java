package dev.vatn.examples.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NativeLibLoader
 *
 * Central helper that loads native shared libraries (.so / .dylib / .dll)
 * and provides a cached {@link SymbolLookup} for each library path.
 *
 * Why not {@code System.loadLibrary()}?
 * —  loadLibrary uses the JVM's own classloader-scoped native cache and
 *    requires the library to be on java.library.path.
 * —  FFM's SymbolLookup.libraryLookup() accepts an absolute Path, works
 *    with plugin JARs that ship their own native artefacts, and is the
 *    correct pairing for the FFM MethodHandle API.
 *
 * Thread safety: The lookup map is concurrent; individual SymbolLookup
 * instances are safe to share across threads.
 */
public final class NativeLibLoader {

    /** Global arena — lives for the duration of the JVM. */
    private static final Arena GLOBAL = Arena.global();

    /** Cache: absolute library path → SymbolLookup */
    private static final ConcurrentHashMap<String, SymbolLookup> CACHE =
            new ConcurrentHashMap<>();

    private NativeLibLoader() {}

    /**
     * Load (or return cached) a native library by absolute path.
     *
     * @param libPath  absolute path to the shared library
     * @return         a {@link SymbolLookup} over the library's exports
     * @throws UnsatisfiedLinkError if the library cannot be loaded
     */
    public static SymbolLookup load(Path libPath) {
        return CACHE.computeIfAbsent(
                libPath.toAbsolutePath().toString(),
                key -> SymbolLookup.libraryLookup(libPath, GLOBAL)
        );
    }

    /**
     * Convenience: resolve a library from the plugin's own directory.
     * Plugins should ship native artefacts next to their JAR.
     *
     * @param pluginDir   directory that contains the shared library
     * @param libBaseName base name without prefix/suffix, e.g. "vatn_text_tool"
     * @return            loaded SymbolLookup
     */
    public static SymbolLookup loadFromDir(Path pluginDir, String libBaseName) {
        String filename = platformLibName(libBaseName);
        return load(pluginDir.resolve(filename));
    }

    /**
     * Returns the platform-specific filename for a library base name.
     * "vatn_text_tool" → "libvatn_text_tool.so" (Linux)
     *                    → "libvatn_text_tool.dylib" (macOS)
     *                    → "vatn_text_tool.dll" (Windows)
     */
    public static String platformLibName(String baseName) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return baseName + ".dll";
        } else if (os.contains("mac")) {
            return "lib" + baseName + ".dylib";
        } else {
            return "lib" + baseName + ".so";
        }
    }

    /** Returns the Linker for the current platform. */
    public static Linker linker() {
        return Linker.nativeLinker();
    }
}
