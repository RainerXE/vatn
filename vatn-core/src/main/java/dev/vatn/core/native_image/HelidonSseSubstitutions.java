package dev.vatn.core.native_image;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM substitution for Helidon SSE builder.
 *
 * Workaround for GraalVM 25 "outlined SB method" parse error in
 * SseEvent$Builder.data(Object) — the parser fails on this method's
 * Object parameter in the context of string-builder outlining.
 * Replace with a direct field store that the analyser can handle.
 */
@TargetClass(className = "io.helidon.http.sse.SseEvent$Builder")
final class Target_SseEvent_Builder {

    @Alias
    private Object data;

    @Substitute
    public Target_SseEvent_Builder data(Object value) {
        this.data = value;
        return this;
    }
}
