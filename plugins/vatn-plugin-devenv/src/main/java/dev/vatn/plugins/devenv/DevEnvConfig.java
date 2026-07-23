package dev.vatn.plugins.devenv;

import java.time.Duration;
import java.util.Set;

/**
 * Configuration for the DevEnv plugin. All fields have sensible defaults;
 * {@link #defaults()} works out of the box.
 */
public final class DevEnvConfig {

    private final boolean scanOnStartup;
    private final Duration refreshInterval;   // ZERO disables background refresh
    private final Duration subprocessTimeout; // per subprocess call
    private final Duration scanTimeout;       // global hard cap for a full scan
    private final Set<String> skipModules;    // scanner module names to skip

    private DevEnvConfig(Builder b) {
        this.scanOnStartup     = b.scanOnStartup;
        this.refreshInterval   = b.refreshInterval;
        this.subprocessTimeout = b.subprocessTimeout;
        this.scanTimeout       = b.scanTimeout;
        this.skipModules       = Set.copyOf(b.skipModules);
    }

    public static DevEnvConfig defaults() { return builder().build(); }
    public static Builder builder()       { return new Builder(); }

    public boolean isScanOnStartup()       { return scanOnStartup; }
    public Duration getRefreshInterval()   { return refreshInterval; }
    public Duration getSubprocessTimeout() { return subprocessTimeout; }
    public Duration getScanTimeout()       { return scanTimeout; }
    public boolean shouldSkip(String module) { return skipModules.contains(module); }

    public static final class Builder {
        private boolean    scanOnStartup     = true;
        private Duration   refreshInterval   = Duration.ofMinutes(30);
        private Duration   subprocessTimeout = Duration.ofSeconds(10);
        private Duration   scanTimeout       = Duration.ofSeconds(30);
        private Set<String> skipModules      = Set.of();

        public Builder scanOnStartup(boolean v)     { scanOnStartup = v;     return this; }
        public Builder refreshInterval(Duration v)  { refreshInterval = v;   return this; }
        public Builder subprocessTimeout(Duration v){ subprocessTimeout = v; return this; }
        public Builder scanTimeout(Duration v)      { scanTimeout = v;       return this; }
        public Builder skipModules(Set<String> v)   { skipModules = v;       return this; }
        public Builder noBackgroundRefresh()        { refreshInterval = Duration.ZERO; return this; }

        public DevEnvConfig build() { return new DevEnvConfig(this); }
    }
}
