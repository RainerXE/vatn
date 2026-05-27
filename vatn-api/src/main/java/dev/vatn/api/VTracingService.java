package dev.vatn.api;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Pluggable distributed tracing SPI.
 *
 * <p>The default registered implementation is a no-op. An OTLP-enabled implementation
 * is auto-registered when {@code VATN_OTLP_ENDPOINT} is set in the environment.
 *
 * <pre>{@code
 * VTracingService tracing = context.getService(VTracingService.class).orElseThrow();
 * VTracingService.Span span = tracing.start("my-operation", Map.of("dag.id", dagId));
 * try {
 *     // ... do work ...
 *     span.finish();
 * } catch (Exception e) {
 *     span.error(e.getMessage());
 *     throw e;
 * }
 * }</pre>
 */
@VatnApi(since = "1.0")
public interface VTracingService extends VService {

    /** An active tracing span. Always call {@link #finish()} or {@link #error(String)}. */
    interface Span {
        void tag(String key, String value);
        void log(String event);
        void error(String message);
        void finish();

        static Span noop() {
            return new Span() {
                public void tag(String k, String v) {}
                public void log(String e) {}
                public void error(String m) {}
                public void finish() {}
            };
        }
    }

    /**
     * Starts a new span. The caller MUST call {@link Span#finish()} or {@link Span#error(String)}
     * in a finally block to avoid trace leaks.
     */
    Span start(String operationName, Map<String, String> tags);

    /** Returns true if tracing is active (i.e. an OTLP endpoint is configured). */
    boolean isEnabled();

    /**
     * Convenience: run {@code body} inside a span, finishing it automatically.
     * On exception the span is marked as error before re-throwing.
     */
    default <T> T traced(String operationName, Map<String, String> tags, Supplier<T> body) {
        Span span = start(operationName, tags);
        try {
            T result = body.get();
            span.finish();
            return result;
        } catch (Exception e) {
            span.error(e.getMessage());
            throw e;
        }
    }

    /** No-op implementation — zero overhead, safe to use as default. */
    static VTracingService noop() {
        return new VTracingService() {
            public Span start(String op, Map<String, String> tags) { return Span.noop(); }
            public boolean isEnabled() { return false; }
        };
    }
}
