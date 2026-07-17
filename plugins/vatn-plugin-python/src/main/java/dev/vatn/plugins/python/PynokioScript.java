package dev.vatn.plugins.python;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Data model for a Pinokio-compatible {@code run[]} script.
 *
 * <p>Compatible with the Pinokio script format documented at
 * <a href="https://docs.pinokio.computer">docs.pinokio.computer</a>.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "run": [
 *     { "method": "shell.run",
 *       "params": { "message": "pip install -r requirements.txt",
 *                   "path": "./app", "venv": "myenv" } },
 *     { "method": "shell.run",
 *       "params": { "message": "python server.py", "venv": "myenv",
 *                   "daemon": true, "autoRestart": true } }
 *   ]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PynokioScript(
    @JsonProperty("run") List<Step> run
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
        @JsonProperty("method") String method,
        @JsonProperty("params") Map<String, Object> params,
        @JsonProperty("id")     String id        // optional: store output as local var
    ) {
        /** Convenience: get a string param. */
        public String str(String key) {
            Object v = params == null ? null : params.get(key);
            return v == null ? null : v.toString();
        }

        /** Convenience: get a boolean param (default false). */
        public boolean bool(String key) {
            Object v = params == null ? null : params.get(key);
            if (v instanceof Boolean b) return b;
            return "true".equalsIgnoreCase(String.valueOf(v));
        }

        @SuppressWarnings("unchecked")
        public Map<String, String> envMap() {
            Object v = params == null ? null : params.get("env");
            if (v instanceof Map<?, ?> m) {
                return (Map<String, String>) m;
            }
            return Map.of();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PynokioScript fromJson(String json) throws IOException {
        return MAPPER.readValue(json, PynokioScript.class);
    }

    public static PynokioScript fromFile(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), PynokioScript.class);
    }

    public String toJson() throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    /** Validates the script for required fields. Returns list of errors; empty = valid. */
    public List<String> validate() {
        if (run == null || run.isEmpty()) return List.of("'run' array is missing or empty");
        var errors = new java.util.ArrayList<String>();
        for (int i = 0; i < run.size(); i++) {
            Step s = run.get(i);
            if (s.method() == null || s.method().isBlank()) {
                errors.add("step[" + i + "]: 'method' is required");
            }
        }
        return errors;
    }
}
