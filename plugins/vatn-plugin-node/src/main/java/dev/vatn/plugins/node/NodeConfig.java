package dev.vatn.plugins.node;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for the Node.js runtime plugin.
 *
 * <pre>{@code
 * runner.addPlugin(new NodePlugin(
 *     NodeConfig.builder()
 *         .nodeBinary("node")
 *         .npmBinary("npm")
 *         .appsDir(Paths.get(".vatn/node/apps"))
 *         .restartDelayMs(3_000)
 *         .build()
 * ));
 * }</pre>
 */
public record NodeConfig(
    String       nodeBinary,
    String       npmBinary,
    String       npxBinary,
    Path         appsDir,
    int          restartDelayMs,
    int          maxLogLines,
    boolean      allowNetwork,
    List<String> allowedEnvVars
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String       nodeBinary     = null;
        private String       npmBinary      = null;
        private String       npxBinary      = null;
        private Path         appsDir        = null;
        private int          restartDelayMs = 3_000;
        private int          maxLogLines    = 500;
        private boolean      allowNetwork   = true;
        private List<String> allowedEnvVars = List.of("PATH","HOME","LANG","NODE_ENV","CI");

        public Builder nodeBinary(String b)        { nodeBinary     = b; return this; }
        public Builder npmBinary(String b)         { npmBinary      = b; return this; }
        public Builder npxBinary(String b)         { npxBinary      = b; return this; }
        public Builder appsDir(Path p)             { appsDir        = p; return this; }
        public Builder restartDelayMs(int ms)      { restartDelayMs = ms; return this; }
        public Builder maxLogLines(int n)          { maxLogLines    = n; return this; }
        public Builder allowNetwork(boolean b)     { allowNetwork   = b; return this; }
        public Builder allowedEnvVars(List<String> v){ allowedEnvVars = v; return this; }

        public NodeConfig build() {
            return new NodeConfig(nodeBinary, npmBinary, npxBinary, appsDir,
                                  restartDelayMs, maxLogLines, allowNetwork, allowedEnvVars);
        }
    }
}
