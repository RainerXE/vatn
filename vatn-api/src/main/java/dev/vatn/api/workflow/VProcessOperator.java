package dev.vatn.api.workflow;

import dev.vatn.api.VatnApi;

/**
 * Standard operator for executing OS processes.
 * Backed by {@link dev.vatn.api.VProcessService}.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VProcessOperator extends VOperator {
    String TYPE = "system.process";

    @Override
    default String operatorType() {
        return TYPE;
    }
}
