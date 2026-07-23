package dev.vatn.plugins.wasm;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for the WASM plugin.
 *
 * <p>Use {@link Builder} to construct:
 * <pre>{@code
 * WasmConfig config = WasmConfig.builder()
 *     .moduleDir(ctx.getWorkspacePath().resolve(".vatn/wasm"))
 *     .maxLinearMemoryPages(256)       // 256 × 64 KiB = 16 MiB
 *     .autoLoadDir(true)               // load all .wasm files from moduleDir at startup
 *     .readOnlyFs(true)                // WASI modules see workspace read-only
 *     .build();
 * }</pre>
 */
public record WasmConfig(
    Path   moduleDir,           // where .wasm files are stored; defaults to .vatn/wasm
    int    maxLinearMemoryPages, // 0 = Chicory default (unlimited)
    boolean autoLoadDir,        // load all .wasm files from moduleDir at startup
    boolean readOnlyFs,         // WASI fs grants are read-only (safer default)
    List<String> allowedEnvVars // env vars forwarded to WASI processes (empty = none)
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Path    moduleDir           = null;   // resolved against workspace at init
        private int     maxLinearMemoryPages = 0;
        private boolean autoLoadDir          = true;
        private boolean readOnlyFs           = true;
        private List<String> allowedEnvVars = List.of("PATH", "HOME", "LANG");

        public Builder moduleDir(Path d)              { moduleDir            = d;  return this; }
        public Builder maxLinearMemoryPages(int p)    { maxLinearMemoryPages = p;  return this; }
        public Builder autoLoadDir(boolean b)         { autoLoadDir          = b;  return this; }
        public Builder readOnlyFs(boolean b)          { readOnlyFs           = b;  return this; }
        public Builder allowedEnvVars(List<String> v) { allowedEnvVars       = v;  return this; }

        public WasmConfig build() {
            return new WasmConfig(moduleDir, maxLinearMemoryPages, autoLoadDir,
                                  readOnlyFs, allowedEnvVars);
        }
    }
}
