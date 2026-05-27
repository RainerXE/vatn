package dev.vatn.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Java binding for the vatn-plugin.schema.json Universal Plugin Contract (VATN 1.0).
 *
 * <p>Deserialize a {@code vatn-plugin.json} manifest with Jackson:
 * <pre>
 * VPluginManifest m = new ObjectMapper().readValue(file, VPluginManifest.class);
 * </pre>
 *
 * <p>Required fields: {@code id}, {@code name}, {@code version}, {@code execution}.
 * All other fields are optional.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VPluginManifest {

    private String id;
    private String name;
    private String version;
    private String vatnVersion;
    private Execution execution;
    private List<Capability> capabilities;

    public String getId()              { return id; }
    public void setId(String id)       { this.id = id; }

    public String getName()            { return name; }
    public void setName(String name)   { this.name = name; }

    public String getVersion()                   { return version; }
    public void setVersion(String version)       { this.version = version; }

    /** Minimum VATN runtime version (e.g. {@code >=1.0.0}). Null means any version. */
    public String getVatnVersion()               { return vatnVersion; }
    public void setVatnVersion(String v)         { this.vatnVersion = v; }

    public Execution getExecution()              { return execution; }
    public void setExecution(Execution e)        { this.execution = e; }

    /** Security capabilities requested by this plugin. May be null or empty. */
    public List<Capability> getCapabilities()           { return capabilities; }
    public void setCapabilities(List<Capability> caps)  { this.capabilities = caps; }

    // ── Execution ────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Execution {

        /** How the plugin runs within the VATN node. */
        public enum Mode {
            /** Loaded into the VATN JVM via PF4J. */
            IN_PROCESS_JVM,
            /** Launched as a separate OS process; communicates over OIPC. */
            OUT_OF_PROCESS_BIN,
            /** Linked as a native shared library via Panama FFI. */
            FFI_NATIVE
        }

        /** IPC transport used to reach the plugin process. */
        public enum Transport {
            IN_PROCESS,
            UDS,
            TCP_LOOPBACK,
            MQTT
        }

        private String mode;
        private String transport;
        private String entrypoint;
        private String ffiLibrary;

        /** Execution mode string (matches {@link Mode} name). */
        public String getMode()                  { return mode; }
        public void setMode(String mode)         { this.mode = mode; }

        public Mode getModeEnum() {
            return mode != null ? Mode.valueOf(mode) : null;
        }

        /** IPC transport string (matches {@link Transport} name). May be null. */
        public String getTransport()             { return transport; }
        public void setTransport(String t)       { this.transport = t; }

        /** Launch command for {@code OUT_OF_PROCESS_BIN} plugins. */
        public String getEntrypoint()            { return entrypoint; }
        public void setEntrypoint(String e)      { this.entrypoint = e; }

        /** Shared library path for {@code FFI_NATIVE} plugins. */
        public String getFfiLibrary()            { return ffiLibrary; }
        public void setFfiLibrary(String f)      { this.ffiLibrary = f; }
    }

    // ── Capability ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Capability {

        /** All security capability types supported by the VATN 1.0 runtime. */
        public enum Type {
            // OIPC messaging declarations
            PUBLISHES,
            SUBSCRIBES,
            // File-system permissions
            FILE_READ,
            FILE_WRITE,
            FILE_EXEC,
            // Network permissions
            NET_OUT,
            NET_IN,
            // Process / system permissions
            PROCESS_SPAWN,
            // Secret-store access
            SECRET_READ,
            SECRET_WRITE,
            // Database access (table-prefix scoped)
            DB_READ,
            DB_WRITE
        }

        private String type;
        private String channel;

        /** Capability type string (matches {@link Type} name). */
        public String getType()              { return type; }
        public void setType(String type)     { this.type = type; }

        public Type getTypeEnum() {
            return type != null ? Type.valueOf(type) : null;
        }

        /**
         * Scope of the capability.
         * <ul>
         *   <li>PUBLISHES/SUBSCRIBES — OIPC channel name or pattern</li>
         *   <li>FILE_* — path prefix (e.g. {@code /data})</li>
         *   <li>NET_* — host or CIDR (e.g. {@code *} = unrestricted)</li>
         *   <li>DB_* — table name prefix (e.g. {@code analytics_})</li>
         * </ul>
         * May be null for capabilities that have no meaningful scope (e.g. {@code PROCESS_SPAWN}).
         */
        public String getChannel()           { return channel; }
        public void setChannel(String c)     { this.channel = c; }
    }
}
