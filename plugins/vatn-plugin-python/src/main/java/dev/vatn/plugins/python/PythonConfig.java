package dev.vatn.plugins.python;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for the Python runtime plugin.
 *
 * <pre>{@code
 * runner.addPlugin(new PythonPlugin(
 *     PythonConfig.builder()
 *         .pythonBinary("python3")      // override auto-detection
 *         .envsDir(Paths.get(".vatn/python/envs"))
 *         .appsDir(Paths.get(".vatn/python/apps"))
 *         .preferUv(true)               // prefer uv over pip
 *         .restartDelayMs(3_000)
 *         .build()
 * ));
 * }</pre>
 */
public record PythonConfig(
    String       pythonBinary,    // null = auto-detect
    Path         envsDir,         // where venvs are stored
    Path         appsDir,         // where app definitions live
    boolean      preferUv,        // use uv pip install if available
    int          restartDelayMs,  // delay before auto-restart on crash
    int          maxLogLines,     // circular log buffer size per process
    List<String> allowedEnvVars   // forwarded to subprocesses
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String       pythonBinary   = null;
        private Path         envsDir        = null;
        private Path         appsDir        = null;
        private boolean      preferUv       = true;
        private int          restartDelayMs = 3_000;
        private int          maxLogLines    = 500;
        private List<String> allowedEnvVars = List.of("PATH","HOME","LANG","JAVA_HOME","CI");

        public Builder pythonBinary(String b)      { pythonBinary   = b; return this; }
        public Builder envsDir(Path p)             { envsDir        = p; return this; }
        public Builder appsDir(Path p)             { appsDir        = p; return this; }
        public Builder preferUv(boolean b)         { preferUv       = b; return this; }
        public Builder restartDelayMs(int ms)      { restartDelayMs = ms; return this; }
        public Builder maxLogLines(int n)          { maxLogLines    = n; return this; }
        public Builder allowedEnvVars(List<String> v) { allowedEnvVars = v; return this; }

        public PythonConfig build() {
            return new PythonConfig(pythonBinary, envsDir, appsDir, preferUv,
                                    restartDelayMs, maxLogLines, allowedEnvVars);
        }
    }
}
